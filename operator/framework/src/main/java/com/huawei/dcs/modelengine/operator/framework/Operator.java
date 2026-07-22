package com.huawei.dcs.modelengine.operator.framework;

import com.huawei.dcs.modelengine.operator.framework.metrics.MetricsServer;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import com.huawei.dcs.modelengine.operator.framework.retry.ExponentialBackoffRetryPolicy;
import com.huawei.dcs.modelengine.operator.framework.retry.RateLimiter;
import com.huawei.dcs.modelengine.operator.framework.retry.RetryPolicy;
import com.huawei.dcs.modelengine.operator.framework.source.ReconciliationQueue;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventSource;
import com.huawei.dcs.modelengine.operator.framework.source.SourceConfiguration;
import com.huawei.dcs.modelengine.operator.framework.source.SourceRole;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Launcher for registering controllers and running their informer/worker loops.
 */
public final class Operator implements AutoCloseable {
    private static final int DEFAULT_WORKER_THREADS = 1;
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final Supplier<KubernetesClient> clientSupplier;
    private final List<ControllerRegistration<?>> registrations = new ArrayList<>();
    private final ConcurrentHashMap<ControllerRegistration<?>, Controller<?>> eventSources = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private String namespace;
    private boolean clusterScoped;
    private int workerThreads = DEFAULT_WORKER_THREADS;
    private boolean shutdownHookEnabled = true;
    private KubernetesClient client;
    private SharedInformerFactory informerFactory;
    private ExecutorService workerPool;
    private ScheduledExecutorService retryScheduler;
    private Thread shutdownHook;
    private volatile boolean running;
    private RetryPolicy retryPolicy = new ExponentialBackoffRetryPolicy();
    private RateLimiter rateLimiter = new RateLimiter();
    private MetricsServer metricsServer;
    private final ConcurrentHashMap<String, Integer> retryAttempts = new ConcurrentHashMap<>();
    private final AtomicLong reconcileErrors = new AtomicLong();

    public Operator() {
        this(() -> new KubernetesClientBuilder().build());
    }

    public Operator(String namespace) {
        this();
        this.namespace = namespace;
    }

    public Operator(KubernetesClient client) {
        this(() -> client);
    }

    public Operator(Supplier<KubernetesClient> clientSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier must not be null");
    }

    public Operator withNamespace(String namespace) {
        this.namespace = namespace;
        this.clusterScoped = false;
        return this;
    }

    public Operator withClusterScope() {
        this.clusterScoped = true;
        return this;
    }

    public Operator withWorkerThreads(int workerThreads) {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be at least 1");
        }
        this.workerThreads = workerThreads;
        return this;
    }

    public Operator withShutdownHookEnabled(boolean shutdownHookEnabled) {
        this.shutdownHookEnabled = shutdownHookEnabled;
        return this;
    }

    public Operator withRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        return this;
    }

    public Operator withRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
        return this;
    }

    public Operator withMetricsServer(MetricsServer metricsServer) {
        this.metricsServer = Objects.requireNonNull(metricsServer, "metricsServer must not be null");
        return this;
    }

    long reconcileErrorCount() {
        return reconcileErrors.get();
    }

    public <T extends HasMetadata> void register(Class<T> resourceClass, Reconciler<T> reconciler) {
        register(new ControllerRegistration<>(resourceClass, reconciler, List.of()));
    }

    public <T extends HasMetadata> void register(ControllerRegistration<T> registration) {
        Objects.requireNonNull(registration, "registration must not be null");
        synchronized (lifecycleLock) {
            if (running) {
                throw new IllegalStateException("Cannot register controllers after operator start");
            }
            registrations.add(registration);
        }
    }

    public List<ResourceEventSource<?>> eventSources() {
        synchronized (lifecycleLock) {
            return eventSources.values().stream()
                    .flatMap(controller -> controller.eventSources().stream())
                    .toList();
        }
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            client = clientSupplier.get();
            informerFactory = createInformerFactory(client);
            workerPool = Executors.newFixedThreadPool(workerThreads);
            retryScheduler = Executors.newSingleThreadScheduledExecutor();
            running = true;

            for (ControllerRegistration<?> registration : registrations) {
                startController(registration);
            }

            informerFactory.startAllRegisteredInformers();
            addShutdownHook();
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            if (!running && client == null) {
                return;
            }
            running = false;
            removeShutdownHook();
            if (informerFactory != null) {
                informerFactory.stopAllRegisteredInformers();
            }
            if (workerPool != null) {
                workerPool.shutdownNow();
                awaitWorkerStop();
            }
            if (retryScheduler != null) {
                retryScheduler.shutdownNow();
            }
            if (client != null) {
                client.close();
            }
            eventSources.clear();
            retryAttempts.clear();
            informerFactory = null;
            workerPool = null;
            retryScheduler = null;
            client = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    private <T extends HasMetadata> void startController(ControllerRegistration<T> registration) {
        Controller<T> controller = createController(registration);
        eventSources.put(registration, controller);
        for (int i = 0; i < workerThreads; i++) {
            workerPool.submit(() -> runWorker(registration));
        }
    }

    private <T extends HasMetadata> void runWorker(ControllerRegistration<T> registration) {
        Reconciler<T> reconciler = registration.reconciler();
        String controller = registration.resourceClass().getSimpleName();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Controller<T> controllerRegistration = controller(registration);
                Request request = controllerRegistration.queue().poll(1, TimeUnit.SECONDS);
                if (request == null) {
                    continue;
                }
                String resourceKey = key(request);
                if (!rateLimiter.canProcess(resourceKey)) {
                    scheduleRequeue(registration, request, rateLimiter.minimumInterval());
                    continue;
                }
                T resource = controllerRegistration.primaryEventSource().getInformer().getStore().getByKey(resourceKey);
                if (resource != null) {
                    rateLimiter.record(resourceKey);
                    long start = System.nanoTime();
                    Result result = reconcile(reconciler, request, resource);
                    recordReconcileMetrics(controller, result, Duration.ofNanos(System.nanoTime() - start));
                    handleResult(registration, request, resourceKey, result);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recordReconcileMetrics(String controller, Result result, Duration duration) {
        if (metricsServer == null) {
            return;
        }
        metricsServer.recordReconcile(controller, resultTag(result));
        metricsServer.recordReconcileDuration(controller, duration);
        if (result != null && result.error() != null) {
            metricsServer.recordReconcileError(controller);
        }
    }

    private String resultTag(Result result) {
        if (result == null) {
            return "success";
        }
        if (result.error() != null) {
            return "error";
        }
        if (result.requeue()) {
            return "requeue";
        }
        return "success";
    }

    private <T extends HasMetadata> Result reconcile(Reconciler<T> reconciler, Request request, T resource) {
        try {
            return reconciler.reconcile(request, resource);
        } catch (Exception exception) {
            return Result.error(exception);
        }
    }

    private <T extends HasMetadata> void handleResult(ControllerRegistration<T> registration, Request request, String resourceKey,
            Result result) {
        if (result == null) {
            retryAttempts.remove(resourceKey);
            return;
        }
        if (result.error() != null) {
            reconcileErrors.incrementAndGet();
            int attempt = retryAttempts.merge(resourceKey, 1, Integer::sum) - 1;
            try {
                scheduleRequeue(registration, request, retryPolicy.nextDelay(attempt));
            } catch (IllegalArgumentException ignored) {
                retryAttempts.remove(resourceKey);
            }
            return;
        }
        retryAttempts.remove(resourceKey);
        if (result.requeue()) {
            scheduleRequeue(registration, request, result.requeueAfter());
        }
    }

    private <T extends HasMetadata> void scheduleRequeue(ControllerRegistration<T> registration, Request request, Duration delay) {
        if (retryScheduler == null || retryScheduler.isShutdown()) {
            return;
        }
        Duration effectiveDelay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
        try {
            retryScheduler.schedule(() -> {
                if (running) {
                    controller(registration).queue().offer(request);
                }
            }, effectiveDelay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Operator is stopping; do not enqueue more work.
        }
    }

    private <T extends HasMetadata> Controller<T> createController(ControllerRegistration<T> registration) {
        BlockingQueue<Request> queue = new ReconciliationQueue();
        ResourceEventSource<T> primaryEventSource = createPrimaryEventSource(registration, queue);
        List<ResourceEventSource<?>> sources = new ArrayList<>();
        sources.add(primaryEventSource);
        for (SecondaryWatch<T, ?> watch : registration.secondaryWatches()) {
            sources.add(createSecondaryEventSource(watch, queue, registration.resyncPeriod().toMillis()));
        }
        return new Controller<>(registration, queue, primaryEventSource, List.copyOf(sources));
    }

    private <T extends HasMetadata> ResourceEventSource<T> createPrimaryEventSource(
            ControllerRegistration<T> registration,
            BlockingQueue<Request> queue) {
        SourceConfiguration<T> configuration = new SourceConfiguration<>(
                registration.resourceClass().getSimpleName(),
                registration.resourceClass(),
                SourceRole.PRIMARY,
                null,
                registration.generationChangeFilter());
        return new ResourceEventSource<>(informerFactory, configuration, queue, registration.resyncPeriod().toMillis());
    }

    private <P extends HasMetadata, S extends HasMetadata> ResourceEventSource<S> createSecondaryEventSource(
            SecondaryWatch<P, S> watch,
            BlockingQueue<Request> queue,
            long resyncPeriodMs) {
        SourceConfiguration<S> configuration = new SourceConfiguration<>(
                watch.name(),
                watch.resourceClass(),
                SourceRole.SECONDARY,
                watch.mapper());
        return new ResourceEventSource<>(informerFactory, configuration, queue, resyncPeriodMs);
    }

    @SuppressWarnings("unchecked")
    private <T extends HasMetadata> Controller<T> controller(ControllerRegistration<T> registration) {
        Controller<?> controller = eventSources.get(registration);
        if (controller == null) {
            throw new IllegalStateException("No event source registered for " + registration.resourceClass().getName());
        }
        return (Controller<T>) controller;
    }

    private Optional<String> namespace() {
        return Optional.ofNullable(namespace).filter(value -> !value.isBlank());
    }

    @SuppressWarnings("deprecation")
    private SharedInformerFactory createInformerFactory(KubernetesClient client) {
        if (clusterScoped) {
            return client.informers();
        }
        String effectiveNamespace = namespace().orElseGet(client::getNamespace);
        if (effectiveNamespace == null || effectiveNamespace.isBlank()) {
            effectiveNamespace = "default";
        }
        return client.informers().inNamespace(effectiveNamespace);
    }

    private String key(Request request) {
        if (request.namespace() == null || request.namespace().isBlank()) {
            return request.name();
        }
        return request.namespace() + "/" + request.name();
    }

    private void addShutdownHook() {
        if (!shutdownHookEnabled || shutdownHook != null) {
            return;
        }
        shutdownHook = new Thread(this::stop, "operator-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void removeShutdownHook() {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down.
        } finally {
            shutdownHook = null;
        }
    }

    private void awaitWorkerStop() {
        try {
            if (!workerPool.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workerPool.shutdownNow();
        }
    }

    private record Controller<T extends HasMetadata>(
            ControllerRegistration<T> registration,
            BlockingQueue<Request> queue,
            ResourceEventSource<T> primaryEventSource,
            List<ResourceEventSource<?>> eventSources) {
    }
}
