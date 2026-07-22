package com.huawei.dcs.modelengine.operator.framework.source;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger;
import com.huawei.dcs.modelengine.operator.framework.reconciler.TriggerRole;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceEventSourceTest {
    @Mock
    private KubernetesClient client;

    @Mock
    private SharedInformerFactory informerFactory;

    @Mock
    private SharedIndexInformer<ConfigMap> informer;

    @Test
    void shouldCreateInformerWithDefaultResyncPeriodAndStartStopIt() {
        when(client.informers()).thenReturn(informerFactory);
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, ResourceEventSource.DEFAULT_RESYNC_PERIOD_MS))
                .thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);
        when(informer.hasSynced()).thenReturn(true);

        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        SourceConfiguration<ConfigMap> configuration = primaryConfiguration();

        ResourceEventSource<ConfigMap> eventSource = new ResourceEventSource<>(
                client,
                configuration,
                queue,
                ResourceEventSource.DEFAULT_RESYNC_PERIOD_MS);

        assertEquals(ResourceEventSource.DEFAULT_RESYNC_PERIOD_MS, eventSource.resyncPeriodMs());
        eventSource.start();
        assertTrue(eventSource.hasSynced());
        eventSource.stop();

        verify(informer).stop();
    }

    @Test
    void shouldEnqueuePrimaryRequestsForAddUpdateAndDeleteEvents() {
        when(client.informers()).thenReturn(informerFactory);
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);

        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        ResourceEventSource<ConfigMap> eventSource = new ResourceEventSource<>(client, primaryConfiguration(), queue, 5_000L);
        assertEquals(5_000L, eventSource.resyncPeriodMs());
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onAdd(configMap("demo", "created"));
        handler.onUpdate(configMap("demo", "old"), configMap("demo", "updated"));
        handler.onDelete(configMap("demo", "deleted"), false);

        assertEquals(3, queue.size());
        assertRequest(queue.poll(), "demo", "created", ResourceEventType.ADD, TriggerRole.PRIMARY);
        assertRequest(queue.poll(), "demo", "updated", ResourceEventType.UPDATE, TriggerRole.PRIMARY);
        assertRequest(queue.poll(), "demo", "deleted", ResourceEventType.DELETE, TriggerRole.PRIMARY);
    }

    @Test
    void shouldMapSecondaryEventsToPrimaryRequestsWithSecondaryTriggers() {
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);
        List<ResourceEventType> mappedTypes = new ArrayList<>();
        List<ConfigMap> oldResources = new ArrayList<>();
        ResourceMapper<ConfigMap, ConfigMap> mapper = (secondary, event) -> {
            mappedTypes.add(event.type());
            oldResources.add(event.oldResource());
            return List.of(new Request("primary", "mapped-" + event.type().name()));
        };
        SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
                "secondary-config-maps",
                ConfigMap.class,
                SourceRole.SECONDARY,
                mapper);
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();

        ResourceEventSource<ConfigMap> eventSource = new ResourceEventSource<>(informerFactory, configuration, queue, 5_000L);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onAdd(configMap("secondary", "created"));
        ConfigMap oldResource = configMap("secondary", "old");
        handler.onUpdate(oldResource, configMap("secondary", "updated"));
        handler.onDelete(configMap("secondary", "deleted"), false);

        assertEquals(List.of(ResourceEventType.ADD, ResourceEventType.UPDATE, ResourceEventType.DELETE), mappedTypes);
        assertNull(oldResources.get(0));
        assertSame(oldResource, oldResources.get(1));
        assertNull(oldResources.get(2));
        assertEquals(3, queue.size());
        assertRequest(queue.poll(), "primary", "mapped-ADD", ResourceEventType.ADD, TriggerRole.SECONDARY);
        assertRequest(queue.poll(), "primary", "mapped-UPDATE", ResourceEventType.UPDATE, TriggerRole.SECONDARY);
        assertRequest(queue.poll(), "primary", "mapped-DELETE", ResourceEventType.DELETE, TriggerRole.SECONDARY);
    }
    @Test
    void shouldNotEnqueueAnythingWhenSecondaryMapperReturnsEmpty() {
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);
        SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
                "empty-secondary",
                ConfigMap.class,
                SourceRole.SECONDARY,
                (secondary, event) -> java.util.Collections.emptyList());
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();

        new ResourceEventSource<>(informerFactory, configuration, queue, 5_000L);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onAdd(configMap("secondary", "ignored"));
        handler.onUpdate(configMap("secondary", "old"), configMap("secondary", "updated"));
        handler.onDelete(configMap("secondary", "gone"), false);

        assertEquals(0, queue.size());
    }

    @Test
    void shouldEnqueueMultiplePrimaryRequestsForSingleSecondaryEvent() {
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);
        ResourceMapper<ConfigMap, ConfigMap> mapper = (secondary, event) -> List.of(
                new Request("primary", "one"),
                new Request("primary", "two"));
        SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
                "multi-secondary",
                ConfigMap.class,
                SourceRole.SECONDARY,
                mapper);
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();

        ResourceEventSource<ConfigMap> eventSource = new ResourceEventSource<>(informerFactory, configuration, queue, 5_000L);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        ConfigMap secondary = configMap("secondary", "multi");
        handler.onAdd(secondary);

        assertEquals(2, queue.size());
        assertRequest(queue.poll(), "primary", "one", ResourceEventType.ADD, TriggerRole.SECONDARY);
        assertRequest(queue.poll(), "primary", "two", ResourceEventType.ADD, TriggerRole.SECONDARY);
        assertEquals(0, queue.size());
    }

    @Test
    void shouldFilterPrimaryStatusUpdatesWhenGenerationChangeFilterEnabled() {
        when(client.informers()).thenReturn(informerFactory);
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);

        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
                "primary-config-maps", ConfigMap.class, SourceRole.PRIMARY, null, true);
        new ResourceEventSource<>(client, configuration, queue, 5_000L);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onUpdate(configMap("demo", "cm", 1L, null), configMap("demo", "cm", 1L, null));

        assertEquals(0, queue.size());
    }

    @Test
    void shouldNotEnqueuePrimaryUpdatesWhenBothGenerationsAreNullDespiteFilter() {
        when(client.informers()).thenReturn(informerFactory);
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);

        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
                "primary-config-maps", ConfigMap.class, SourceRole.PRIMARY, null, true);
        new ResourceEventSource<>(client, configuration, queue, 5_000L);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onUpdate(configMap("demo", "cm", null, null), configMap("demo", "cm", null, null));

        assertEquals(0, queue.size());
    }

    @Test
    void shouldEnqueuePrimaryDeletionAndFinalizerChangesDespiteFilter() {
        when(client.informers()).thenReturn(informerFactory);
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);

        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
                "primary-config-maps", ConfigMap.class, SourceRole.PRIMARY, null, true);
        new ResourceEventSource<>(client, configuration, queue, 5_000L);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onUpdate(
                configMap("demo", "deleting", 1L, null, "example/protect"),
                configMap("demo", "deleting", 1L, "2026-07-21T00:00:00Z", "example/protect"));
        handler.onUpdate(
                configMap("demo", "finalizing", 1L, "2026-07-21T00:00:00Z", "example/protect"),
                configMap("demo", "finalizing", 1L, "2026-07-21T00:00:00Z"));

        assertEquals(2, queue.size());
        assertRequest(queue.poll(), "demo", "deleting", ResourceEventType.UPDATE, TriggerRole.PRIMARY);
        assertRequest(queue.poll(), "demo", "finalizing", ResourceEventType.UPDATE, TriggerRole.PRIMARY);
    }

    @Test
    void shouldNotFilterSecondaryUpdatesWhenGenerationChangeFilterEnabledOnSecondarySource() {
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);

        ResourceMapper<ConfigMap, ConfigMap> mapper = (secondary, event) ->
                List.of(new Request("primary", "mapped-" + event.type().name()));
        SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
                "secondary-config-maps", ConfigMap.class, SourceRole.SECONDARY, mapper, true);
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        new ResourceEventSource<>(informerFactory, configuration, queue, 5_000L);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onUpdate(configMap("demo", "cm", 1L, null), configMap("demo", "cm", 1L, null));

        assertEquals(1, queue.size());
        assertRequest(queue.poll(), "primary", "mapped-UPDATE", ResourceEventType.UPDATE, TriggerRole.SECONDARY);
    }

    @Test
    void shouldEnqueuePrimaryUpdateWhenOldMetadataIsNullDespiteFilter() {
        when(client.informers()).thenReturn(informerFactory);
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(org.mockito.ArgumentMatchers.any())).thenReturn(informer);

        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
                "primary-config-maps", ConfigMap.class, SourceRole.PRIMARY, null, true);
        new ResourceEventSource<>(client, configuration, queue, 5_000L);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onUpdate(new ConfigMap(), configMap("demo", "cm", 1L, null));

        assertEquals(1, queue.size());
        assertRequest(queue.poll(), "demo", "cm", ResourceEventType.UPDATE, TriggerRole.PRIMARY);
    }


    private ResourceEventHandler<ConfigMap> registeredHandler() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ResourceEventHandler<ConfigMap>> captor = ArgumentCaptor.forClass(ResourceEventHandler.class);
        verify(informer).addEventHandler(captor.capture());
        return captor.getValue();
    }

    private static ConfigMap configMap(String namespace, String name) {
        ConfigMap configMap = new ConfigMap();
        configMap.setMetadata(new ObjectMetaBuilder().withNamespace(namespace).withName(name).build());
        return configMap;
    }

    private static ConfigMap configMap(
            String namespace,
            String name,
            Long generation,
            String deletionTimestamp,
            String... finalizers) {
        ConfigMap configMap = new ConfigMap();
        configMap.setMetadata(new ObjectMetaBuilder()
                .withNamespace(namespace)
                .withName(name)
                .withGeneration(generation)
                .withDeletionTimestamp(deletionTimestamp)
                .withFinalizers(finalizers)
                .build());
        return configMap;
    }

    private static SourceConfiguration<ConfigMap> primaryConfiguration() {
        return new SourceConfiguration<>("primary-config-maps", ConfigMap.class, SourceRole.PRIMARY, null);
    }

    private static void assertRequest(
            Request request,
            String namespace,
            String name,
            ResourceEventType eventType,
            TriggerRole role) {
        assertEquals(namespace, request.namespace());
        assertEquals(name, request.name());
        assertEquals(1, request.triggers().size());
        Trigger trigger = request.triggers().get(0);
        assertEquals(eventType, trigger.eventType());
        assertEquals(role, trigger.role());
    }
}
