/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder.WatchSelector;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.controller.Mappers;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ResourceEvent;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ResourceMapper;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationTrigger;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.TriggerRole;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.cache.Indexer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One Fabric8 informer and worker set for a controller registration.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@Slf4j
final class Fabric8Controller<T extends HasMetadata> implements ControllerRuntime {
    private static final ReconciliationQueue.DurationMillis POLL_TIMEOUT = new ReconciliationQueue.DurationMillis(100);

    private final KubernetesClient client;

    private final ControllerRegistration<T> registration;

    private final OperatorFrameworkProperties.Controller properties;

    private final Duration shutdownTimeout;

    private final OperatorFrameworkMetrics metrics;

    private final ReconciliationQueue queue = new ReconciliationQueue();

    private final ConcurrentHashMap<ResourceKey, T> deletedResources = new ConcurrentHashMap<>();

    private final List<SharedIndexInformer<?>> informers = new ArrayList<>();

    private final ConcurrentHashMap<Class<? extends HasMetadata>, Indexer<?>> caches = new ConcurrentHashMap<>();

    private final ExecutorService workers;

    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean started = new AtomicBoolean();

    private final OperatorFrameworkMetrics.GaugeHandle queueGauge;

    private final OperatorFrameworkMetrics.GaugeHandle informerGauge;

    private volatile boolean stopping;

    private CompletableFuture<Void> stopFuture = CompletableFuture.completedFuture(null);

    private SharedIndexInformer<T> primaryInformer;

    Fabric8Controller(KubernetesClient client, ControllerRegistration<T> registration,
        OperatorFrameworkProperties.Controller properties, Duration shutdownTimeout) {
        this(client, registration, properties, shutdownTimeout, new OperatorFrameworkMetrics(null));
    }

    Fabric8Controller(KubernetesClient client, ControllerRegistration<T> registration,
        OperatorFrameworkProperties.Controller properties, Duration shutdownTimeout, OperatorFrameworkMetrics metrics) {
        this.client = client;
        this.registration = registration;
        this.properties = properties;
        this.shutdownTimeout = shutdownTimeout;
        this.metrics = metrics;
        var prefix = "operator-" + registration.resourceType().getSimpleName().toLowerCase() + "-";
        workers = Executors.newFixedThreadPool(properties.getWorkerThreads(),
            Thread.ofPlatform().daemon(true).name(prefix + "worker-", 0).factory());
        scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(prefix + "scheduler").factory());
        var controller = registration.resourceType().getName();
        queueGauge = metrics.queueDepth(controller, () -> queue.size());
        informerGauge = metrics.informerSynced(controller, () -> isReady() ? 1.0 : 0.0);
    }

    /**
     * Reports whether the controller is started, not stopping, and all informers have synced.
     *
     * @return {@code true} when the controller is ready to serve
     */
    @Override
    public boolean isReady() {
        return started.get() && !stopping && informers.stream().allMatch(SharedIndexInformer::hasSynced);
    }

    /**
     * Starts the informers and worker threads; does nothing when already started.
     *
     * @throws RuntimeException when informer or worker setup fails, after stopping what was started
     */
    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            configureInformers();
            startWorkers();
            informers.forEach(SharedIndexInformer::start);
        } catch (RuntimeException exception) {
            stop();
            throw exception;
        }
    }

    /**
     * Reports whether the controller is started and not stopping.
     *
     * @return {@code true} when the controller is running
     */
    @Override
    public boolean isRunning() {
        return started.get() && !stopping;
    }

    /**
     * Returns the number of reconciliation requests waiting in the queue.
     *
     * @return the current queue depth
     */
    @Override
    public int queueDepth() {
        return queue.size();
    }

    /**
     * Stops the informers and scheduler, discards pending work, and shuts the workers down.
     *
     * @return a stage that completes when the worker threads have terminated
     */
    @Override
    public synchronized CompletionStage<Void> stop() {
        if (!started.get()) {
            return CompletableFuture.completedFuture(null);
        }
        if (stopping) {
            return stopFuture;
        }
        stopping = true;
        queue.discardPending();
        deletedResources.clear();
        informers.forEach(SharedIndexInformer::stop);
        scheduler.shutdownNow();
        queueGauge.close();
        informerGauge.close();
        workers.shutdownNow();
        CompletableFuture.delayedExecutor(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .execute(workers::shutdownNow);
        stopFuture = CompletableFuture.runAsync(this::awaitWorkers);
        return stopFuture;
    }

    private void configureInformers() {
        var resync = resyncPeriod();
        primaryInformer =
            informer(registration.resourceType(), new PrimaryHandler(), resync, registration.watchSelector());
        registration.indexFields()
            .forEach((key, fn) -> primaryInformer.addIndexers(Map.of(key, r -> List.of(fn.apply(r)))));
        caches.put(registration.resourceType(), primaryInformer.getIndexer());
        registration.ownedResources().forEach(this::addOwnedInformer);
        registration.secondaryWatches().forEach(this::addSecondaryInformer);
        if (registration.watchesKubernetesEvents()) {
            addEventInformer(resync);
        }
    }

    private <S extends HasMetadata> SharedIndexInformer<S> informer(Class<S> type, ResourceEventHandler<S> handler,
        Duration resync, Optional<WatchSelector> watchSelector) {
        var resources = client.resources(type);
        var scoped = properties.isClusterScoped() ? resources.inAnyNamespace() : resources.inNamespace(namespace());
        var informer = watchSelector.isPresent() ? scoped.withLabels(watchSelector.get().labels())
            .withFields(watchSelector.get().fields())
            .runnableInformer(resync.toMillis()) : scoped.runnableInformer(resync.toMillis());
        informer.addEventHandler(handler);
        informer.exceptionHandler((startedState, exception) -> {
            onInformerError(exception);
            return true;
        });
        informers.add(informer);
        return informer;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addOwnedInformer(Class<? extends HasMetadata> type) {
        ResourceMapper mapper = Mappers.ownerReferences(registration.resourceType());
        var informer =
            informer((Class) type, new SecondaryHandler(mapper, TriggerRole.OWNED), resyncPeriod(), Optional.empty());
        caches.put(type, informer.getIndexer());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addSecondaryInformer(ControllerRegistration.SecondaryWatch<? extends HasMetadata, T> watch) {
        var informer = informer((Class) watch.resourceType(), new SecondaryHandler(watch.mapper(), TriggerRole.WATCHED),
            resyncPeriod(), Optional.empty());
        caches.put(watch.resourceType(), informer.getIndexer());
    }

    private void addEventInformer(Duration resync) {
        var mapper = Mappers.involvedObject(registration.resourceType());
        var type = registration.resourceType();
        var events = client.v1().events();
        var scoped = properties.isClusterScoped() ? events.inAnyNamespace() : events.inNamespace(namespace());
        if (!properties.isFilterEventsByInvolvedObject()) {
            registerEventInformer(scoped.runnableInformer(resync.toMillis()), mapper);
            return;
        }
        var informer = scoped.withField("involvedObject.kind", HasMetadata.getKind(type))
            .withField("involvedObject.apiVersion", HasMetadata.getApiVersion(type))
            .runnableInformer(resync.toMillis());
        registerEventInformer(informer, mapper);
    }

    private SharedIndexInformer<Event> registerEventInformer(SharedIndexInformer<Event> informer,
        ResourceMapper<Event, T> mapper) {
        informer.addEventHandler(new SecondaryHandler<>(mapper, TriggerRole.KUBERNETES_EVENT));
        informer.exceptionHandler((startedState, exception) -> {
            onInformerError(exception);
            return true;
        });
        informers.add(informer);
        return informer;
    }

    private void onInformerError(Throwable exception) {
        var controller = registration.resourceType().getName();
        log.warn("Informer error for {}", controller, exception);
        metrics.informerError(controller);
    }

    private Duration resyncPeriod() {
        return registration.resyncPeriod().orElse(properties.getResyncPeriod());
    }

    private String namespace() {
        var configured = properties.getNamespace();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        var clientNamespace = client.getNamespace();
        return clientNamespace == null || clientNamespace.isBlank() ? "default" : clientNamespace;
    }

    private void startWorkers() {
        for (var index = 0; index < properties.getWorkerThreads(); index++) {
            workers.execute(this::workerLoop);
        }
    }

    private void workerLoop() {
        try {
            while (!stopping || !queue.isDrained()) {
                queue.poll(POLL_TIMEOUT).ifPresent(this::reconcile);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void reconcile(ReconciliationQueue.Work work) {
        try {
            var resource = resourceFor(work.key());
            if (resource == null) {
                return;
            }
            var context =
                new ReconciliationContext<T>(work.key(), work.triggers(), primaryInformer.getIndexer(), caches);
            var result = registration.reconciler().reconcile(resource, context);
            schedule(result, resource);
            if (result.isDone()) {
                deletedResources.remove(work.key());
            }
            // catch Throwable so a single bad reconcile (e.g. StackOverflowError) cannot kill
            // the worker thread; the aspect chain has already classified/logged the callback failure
        } catch (Throwable exception) {
            deletedResources.remove(work.key());
            log.error("Reconciliation failed for {}", work.key(), exception);
        } finally {
            queue.complete(work.key());
        }
    }

    private T resourceFor(ResourceKey key) {
        var current = primaryInformer.getStore().getByKey(storeKey(key));
        if (current != null) {
            deletedResources.remove(key);
            return current;
        }
        return deletedResources.get(key);
    }

    private void schedule(ReconcileResult result, T resource) {
        Objects.requireNonNull(result, "reconciler result must not be null").requeueDelay().ifPresent(delay -> {
            var trigger = trigger(ResourceEventType.RESYNC, TriggerRole.PRIMARY, resource);
            scheduler.schedule(() -> queue.offer(ResourceReference.from(resource).key(), trigger), delay.toMillis(),
                TimeUnit.MILLISECONDS);
        });
    }

    private void enqueue(ResourceEventType type, TriggerRole role, HasMetadata resource) {
        var reference = ResourceReference.from(resource);
        queue.offer(reference.key(), new ReconciliationTrigger(type, role, reference));
    }

    private ReconciliationTrigger trigger(ResourceEventType type, TriggerRole role, HasMetadata resource) {
        return new ReconciliationTrigger(type, role, ResourceReference.from(resource));
    }

    private String storeKey(ResourceKey key) {
        return key.namespace() == null ? key.name() : key.namespace() + "/" + key.name();
    }

    private void awaitWorkers() {
        try {
            while (!workers.awaitTermination(1, TimeUnit.DAYS)) {
                // Spring's lifecycle timeout invokes the scheduled interrupt; this only observes completion.
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    private final class PrimaryHandler implements ResourceEventHandler<T> {
        /**
         * Enqueues an added primary resource for reconciliation.
         *
         * @param resource the added resource
         */
        @Override
        public void onAdd(T resource) {
            deletedResources.remove(ResourceReference.from(resource).key());
            enqueue(ResourceEventType.ADDED, TriggerRole.PRIMARY, resource);
        }

        /**
         * Enqueues an updated primary resource when the generation filter accepts the change.
         *
         * @param previous the previous state of the resource
         * @param resource the current state of the resource
         */
        @Override
        public void onUpdate(T previous, T resource) {
            deletedResources.remove(ResourceReference.from(resource).key());
            var resync = GenerationFilter.isResync(previous, resource);
            var generationFilter = registration.generationFilter().orElse(properties.isGenerationChangeFilter());
            if (resync || GenerationFilter.accepts(previous, resource, generationFilter)) {
                enqueue(resync ? ResourceEventType.RESYNC : ResourceEventType.UPDATED, TriggerRole.PRIMARY, resource);
            }
        }

        /**
         * Retains a deleted primary resource and enqueues it for a final reconciliation.
         *
         * @param resource the deleted resource
         * @param deletedFinalStateUnknown whether the final state of the resource is unknown
         */
        @Override
        public void onDelete(T resource, boolean deletedFinalStateUnknown) {
            deletedResources.put(ResourceReference.from(resource).key(), resource);
            enqueue(ResourceEventType.DELETED, TriggerRole.PRIMARY, resource);
        }
    }

    private final class SecondaryHandler<S extends HasMetadata> implements ResourceEventHandler<S> {
        private final ResourceMapper<S, T> mapper;

        private final TriggerRole role;

        private SecondaryHandler(ResourceMapper<S, T> mapper, TriggerRole role) {
            this.mapper = mapper;
            this.role = role;
        }

        /**
         * Maps an added secondary resource onto the primary resources to reconcile.
         *
         * @param resource the added secondary resource
         */
        @Override
        public void onAdd(S resource) {
            map(ResourceEvent.added(resource));
        }

        private void map(ResourceEvent<S> event) {
            try {
                var trigger = trigger(event.type(), role, event.resource());
                Objects.requireNonNull(mapper.map(event), "resource mapper result must not be null")
                    .forEach(key -> queue.offer(key, trigger));
            } catch (RuntimeException exception) {
                log.error("Secondary resource mapping failed", exception);
            }
        }

        /**
         * Maps an updated secondary resource onto the primary resources to reconcile.
         *
         * @param previous the previous state of the secondary resource
         * @param resource the current state of the secondary resource
         */
        @Override
        public void onUpdate(S previous, S resource) {
            // event count increments are aggregation churn; new reasons arrive as ADDs
            if (role == TriggerRole.KUBERNETES_EVENT && !GenerationFilter.isResync(previous, resource)) {
                return;
            }
            map(GenerationFilter.isResync(previous, resource)
                ? ResourceEvent.resync(resource)
                : ResourceEvent.updated(previous, resource));
        }

        /**
         * Maps a deleted secondary resource onto the primary resources to reconcile.
         *
         * @param resource the deleted secondary resource
         * @param deletedFinalStateUnknown whether the final state of the resource is unknown
         */
        @Override
        public void onDelete(S resource, boolean deletedFinalStateUnknown) {
            map(ResourceEvent.deleted(resource));
        }
    }
}
