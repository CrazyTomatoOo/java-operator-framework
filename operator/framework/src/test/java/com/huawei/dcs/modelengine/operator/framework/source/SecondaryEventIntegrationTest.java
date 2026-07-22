package com.huawei.dcs.modelengine.operator.framework.source;

import com.huawei.dcs.modelengine.operator.framework.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.SecondaryWatch;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger;
import com.huawei.dcs.modelengine.operator.framework.reconciler.TriggerRole;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecondaryEventIntegrationTest {

    @Mock
    private SharedInformerFactory informerFactory;

    @Mock
    private SharedIndexInformer<ConfigMap> informer;

    @Test
    void shouldEnqueuePrimaryRequestWithSecondaryTriggerFromLabelMapper() {
        ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
            .withReconciler(emptyReconciler())
            .watches("config-map-source", ConfigMap.class, Mappers.byLabel("primary-name"))
            .build();
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        createSecondaryEventSource(registration, queue);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onAdd(configMapWithLabel("secondary-ns", "secondary-1", "primary-1"));

        Request request = queue.poll();
        assertEquals("secondary-ns", request.namespace());
        assertEquals("primary-1", request.name());
        assertEquals(1, request.triggers().size());
        Trigger trigger = request.triggers().get(0);
        assertEquals(ResourceEventType.ADD, trigger.eventType());
        assertEquals(TriggerRole.SECONDARY, trigger.role());
        assertEquals("ConfigMap", trigger.kind());
        assertEquals("secondary-ns", trigger.namespace());
        assertEquals("secondary-1", trigger.name());
    }

    @Test
    void shouldNotEnqueueWhenLabelsMismatch() {
        ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
            .withReconciler(emptyReconciler())
            .watches("config-map-source", ConfigMap.class, Mappers.byLabel("primary-name"))
            .build();
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        createSecondaryEventSource(registration, queue);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();
        ConfigMap secondary = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setNamespace("secondary-ns");
        metadata.setName("secondary-1");
        metadata.setLabels(Map.of("different-label", "primary-1"));
        secondary.setMetadata(metadata);

        handler.onAdd(secondary);

        assertTrue(queue.isEmpty());
    }

    @Test
    void shouldNotEnqueueWhenLabelsMissing() {
        ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
            .withReconciler(emptyReconciler())
            .watches("config-map-source", ConfigMap.class, Mappers.byLabel("primary-name"))
            .build();
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        createSecondaryEventSource(registration, queue);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onAdd(configMap("secondary-ns", "secondary-1"));

        assertTrue(queue.isEmpty());
    }

    @Test
    void shouldMapMultipleSecondariesToSamePrimary() {
        ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
            .withReconciler(emptyReconciler())
            .watches("config-map-source", ConfigMap.class, Mappers.byLabel("primary-name"))
            .build();
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        createSecondaryEventSource(registration, queue);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onAdd(configMapWithLabel("secondary-ns", "secondary-1", "primary-1"));
        handler.onAdd(configMapWithLabel("secondary-ns", "secondary-2", "primary-1"));

        assertEquals(2, queue.size());
        Request first = queue.poll();
        Request second = queue.poll();
        assertEquals("secondary-ns", first.namespace());
        assertEquals("primary-1", first.name());
        assertEquals("secondary-ns", second.namespace());
        assertEquals("primary-1", second.name());
        assertEquals("secondary-1", first.triggers().get(0).name());
        assertEquals("secondary-2", second.triggers().get(0).name());
    }

    @Test
    void shouldEnqueueDeleteEventWithSecondaryTrigger() {
        ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
            .withReconciler(emptyReconciler())
            .watches("config-map-source", ConfigMap.class, Mappers.byLabel("primary-name"))
            .build();
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        createSecondaryEventSource(registration, queue);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onDelete(configMapWithLabel("secondary-ns", "secondary-1", "primary-1"), false);

        Request request = queue.poll();
        assertEquals("secondary-ns", request.namespace());
        assertEquals("primary-1", request.name());
        assertEquals(1, request.triggers().size());
        Trigger trigger = request.triggers().get(0);
        assertEquals(ResourceEventType.DELETE, trigger.eventType());
        assertEquals(TriggerRole.SECONDARY, trigger.role());
    }

    @Test
    void shouldNotEnqueueWhenOwnerReferenceIsMissing() {
        ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
            .withReconciler(emptyReconciler())
            .owns(ConfigMap.class)
            .build();
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        createSecondaryEventSource(registration, queue);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();

        handler.onAdd(configMap("secondary-ns", "secondary-1"));

        assertTrue(queue.isEmpty());
    }

    @Test
    void shouldAppendSecondaryTriggerToExistingRequestIdentity() {
        ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
            .withReconciler(emptyReconciler())
            .watches("config-map-source", ConfigMap.class, Mappers.byLabel("primary-name", "primary-namespace"))
            .build();
        BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
        createSecondaryEventSource(registration, queue);
        ResourceEventHandler<ConfigMap> handler = registeredHandler();
        ConfigMap secondary = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setNamespace("secondary-ns");
        metadata.setName("secondary-1");
        metadata.setLabels(Map.of("primary-name", "primary-1", "primary-namespace", "primary-ns"));
        secondary.setMetadata(metadata);

        handler.onUpdate(configMap("secondary-ns", "old"), secondary);

        Request request = queue.poll();
        assertEquals("primary-ns", request.namespace());
        assertEquals("primary-1", request.name());
        assertEquals(1, request.triggers().size());
        Trigger trigger = request.triggers().get(0);
        assertEquals(ResourceEventType.UPDATE, trigger.eventType());
        assertEquals(TriggerRole.SECONDARY, trigger.role());
    }

    private ResourceEventSource<ConfigMap> createSecondaryEventSource(
            ControllerRegistration<ConfigMap> registration,
            BlockingQueue<Request> queue) {
        @SuppressWarnings("unchecked")
        SecondaryWatch<ConfigMap, ConfigMap> watch = (SecondaryWatch<ConfigMap, ConfigMap>) registration.secondaryWatches().get(0);
        SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
            watch.name(),
            watch.resourceClass(),
            SourceRole.SECONDARY,
            watch.mapper());
        when(informerFactory.sharedIndexInformerFor(ConfigMap.class, 5_000L)).thenReturn(informer);
        when(informer.addEventHandler(any())).thenReturn(informer);
        return new ResourceEventSource<>(informerFactory, configuration, queue, 5_000L);
    }

    private ResourceEventHandler<ConfigMap> registeredHandler() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ResourceEventHandler<ConfigMap>> captor = ArgumentCaptor.forClass(ResourceEventHandler.class);
        verify(informer).addEventHandler(captor.capture());
        return captor.getValue();
    }

    private static Reconciler<ConfigMap> emptyReconciler() {
        return (request, resource) -> Result.done();
    }

    private static ConfigMap configMap(String namespace, String name) {
        ConfigMap configMap = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setNamespace(namespace);
        metadata.setName(name);
        configMap.setMetadata(metadata);
        return configMap;
    }

    private static ConfigMap configMapWithLabel(String namespace, String name, String primaryName) {
        ConfigMap configMap = configMap(namespace, name);
        configMap.getMetadata().setLabels(Map.of("primary-name", primaryName));
        return configMap;
    }
}
