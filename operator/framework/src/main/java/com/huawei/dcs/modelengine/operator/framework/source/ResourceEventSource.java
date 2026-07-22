package com.huawei.dcs.modelengine.operator.framework.source;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger;
import com.huawei.dcs.modelengine.operator.framework.reconciler.TriggerRole;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Fabric8 informer wrapper that translates resource events into reconcile requests.
 *
 * @param <T> watched Kubernetes resource type
 */
public class ResourceEventSource<T extends HasMetadata> {
    public static final long DEFAULT_RESYNC_PERIOD_MS = 60_000L;

    private final SourceConfiguration<T> configuration;
    private final SharedIndexInformer<T> informer;
    private final BlockingQueue<Request> workQueue;
    private final long resyncPeriodMs;
    private final boolean generationChangeFilter;

    public ResourceEventSource(KubernetesClient client, Class<T> resourceClass) {
        this(client, primaryConfiguration(resourceClass), new LinkedBlockingQueue<>(), DEFAULT_RESYNC_PERIOD_MS);
    }

    public ResourceEventSource(KubernetesClient client, Class<T> resourceClass, long resyncPeriodMs) {
        this(client, primaryConfiguration(resourceClass), new LinkedBlockingQueue<>(), resyncPeriodMs);
    }

    public ResourceEventSource(
            KubernetesClient client,
            SourceConfiguration<T> configuration,
            BlockingQueue<Request> workQueue) {
        this(client, configuration, workQueue, DEFAULT_RESYNC_PERIOD_MS);
    }

    public ResourceEventSource(
            KubernetesClient client,
            SourceConfiguration<T> configuration,
            BlockingQueue<Request> workQueue,
            long resyncPeriodMs) {
        this(client, configuration, workQueue, resyncPeriodMs, false);
    }

    public ResourceEventSource(
            KubernetesClient client,
            SourceConfiguration<T> configuration,
            BlockingQueue<Request> workQueue,
            long resyncPeriodMs,
            boolean generationChangeFilter) {
        Objects.requireNonNull(client, "client must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.workQueue = Objects.requireNonNull(workQueue, "workQueue must not be null");
        this.resyncPeriodMs = resyncPeriodMs;
        this.generationChangeFilter = generationChangeFilter;
        this.informer = client.informers().sharedIndexInformerFor(configuration.resourceClass(), resyncPeriodMs);
        this.informer.addEventHandler(new EnqueueingEventHandler());
    }

    public ResourceEventSource(SharedInformerFactory informerFactory, Class<T> resourceClass, long resyncPeriodMs) {
        this(informerFactory, primaryConfiguration(resourceClass), new LinkedBlockingQueue<>(), resyncPeriodMs);
    }

    public ResourceEventSource(
            SharedInformerFactory informerFactory,
            SourceConfiguration<T> configuration,
            BlockingQueue<Request> workQueue) {
        this(informerFactory, configuration, workQueue, DEFAULT_RESYNC_PERIOD_MS);
    }

    public ResourceEventSource(
            SharedInformerFactory informerFactory,
            SourceConfiguration<T> configuration,
            BlockingQueue<Request> workQueue,
            long resyncPeriodMs) {
        this(informerFactory, configuration, workQueue, resyncPeriodMs, false);
    }

    public ResourceEventSource(
            SharedInformerFactory informerFactory,
            SourceConfiguration<T> configuration,
            BlockingQueue<Request> workQueue,
            long resyncPeriodMs,
            boolean generationChangeFilter) {
        Objects.requireNonNull(informerFactory, "informerFactory must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.workQueue = Objects.requireNonNull(workQueue, "workQueue must not be null");
        this.resyncPeriodMs = resyncPeriodMs;
        this.generationChangeFilter = generationChangeFilter;
        this.informer = informerFactory.sharedIndexInformerFor(configuration.resourceClass(), resyncPeriodMs);
        this.informer.addEventHandler(new EnqueueingEventHandler());
    }

    public void start() {
        // The informer is started by SharedInformerFactory.startAllRegisteredInformers().
        // This method is a no-op placeholder for callers that manage their own factory.
    }

    public void stop() {
        this.informer.stop();
    }

    public boolean hasSynced() {
        return this.informer.hasSynced();
    }

    public SharedIndexInformer<T> getInformer() {
        return this.informer;
    }

    public long resyncPeriodMs() {
        return this.resyncPeriodMs;
    }

    private static <T extends HasMetadata> SourceConfiguration<T> primaryConfiguration(Class<T> resourceClass) {
        return new SourceConfiguration<>(resourceClass.getSimpleName(), resourceClass, SourceRole.PRIMARY, null);
    }

    private boolean isGenerationChangeFilterEnabled() {
        // The flag may arrive via SourceConfiguration (Operator path) or via constructor (direct usage).
        return generationChangeFilter || configuration.generationChangeFilter();
    }

    private boolean shouldEnqueue(T oldResource, T newResource) {
        if (oldResource == null || newResource == null) {
            return true;
        }
        ObjectMeta oldMeta = oldResource.getMetadata();
        ObjectMeta newMeta = newResource.getMetadata();
        if (oldMeta == null || newMeta == null) {
            return true;
        }
        // Deletion requested / finalizer progression -> always enqueue
        boolean deletionRequested = newMeta.getDeletionTimestamp() != null
                && oldMeta.getDeletionTimestamp() == null;
        boolean finalizersChanged = !Objects.equals(oldMeta.getFinalizers(), newMeta.getFinalizers());
        if (deletionRequested || finalizersChanged) {
            return true;
        }
        // Generation changed -> enqueue
        return !Objects.equals(oldMeta.getGeneration(), newMeta.getGeneration());
    }

    private void enqueue(T resource, T oldResource, ResourceEventType eventType) {
        if (this.configuration.role() == SourceRole.PRIMARY) {
            enqueuePrimary(resource, eventType);
        } else {
            enqueueSecondary(resource, oldResource, eventType);
        }
    }

    private void enqueuePrimary(T resource, ResourceEventType eventType) {
        ObjectMeta metadata = Objects.requireNonNull(resource.getMetadata(), "resource metadata must not be null");
        this.workQueue.add(new Request(
                metadata.getNamespace(),
                metadata.getName(),
                Trigger.from(resource, eventType, TriggerRole.PRIMARY)));
    }

    private void enqueueSecondary(T resource, T oldResource, ResourceEventType eventType) {
        ResourceEvent<T> event = new ResourceEvent<>(eventType, resource, oldResource);
        Collection<Request> requests = this.configuration.mapper().map(resource, event);
        Trigger trigger = Trigger.from(resource, eventType, TriggerRole.SECONDARY);
        for (Request request : requests) {
            this.workQueue.add(request.withTrigger(trigger));
        }
    }

    private class EnqueueingEventHandler implements ResourceEventHandler<T> {
        @Override
        public void onAdd(T resource) {
            enqueue(resource, null, ResourceEventType.ADD);
        }

        @Override
        public void onUpdate(T oldResource, T newResource) {
            if (configuration.role() == SourceRole.PRIMARY
                    && isGenerationChangeFilterEnabled()
                    && !shouldEnqueue(oldResource, newResource)) {
                return;
            }
            enqueue(newResource, oldResource, ResourceEventType.UPDATE);
        }

        @Override
        public void onDelete(T resource, boolean deletedFinalStateUnknown) {
            enqueue(resource, null, ResourceEventType.DELETE);
        }
    }
}
