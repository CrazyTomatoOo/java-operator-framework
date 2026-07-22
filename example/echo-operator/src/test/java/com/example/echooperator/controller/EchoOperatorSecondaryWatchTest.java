package com.example.echooperator.controller;

import com.example.echooperator.api.v1alpha2.EchoResource;
import com.huawei.dcs.modelengine.operator.framework.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.Operator;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger;
import com.huawei.dcs.modelengine.operator.framework.reconciler.TriggerRole;
import com.huawei.dcs.modelengine.operator.framework.source.Mappers;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventType;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedInformerEventListener;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Store;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EchoOperatorSecondaryWatchTest {

    @Test
    @SuppressWarnings("unchecked")
    void configMapEventTriggersEchoReconciliationWithSecondaryTrigger() throws Exception {
        KubernetesClient client = mock(KubernetesClient.class);
        SharedIndexInformer<EchoResource> primaryInformer = mock(SharedIndexInformer.class);
        SharedIndexInformer<ConfigMap> secondaryInformer = mock(SharedIndexInformer.class);
        TestInformerFactory namespaceFactory = new TestInformerFactory(Map.of(
                EchoResource.class, primaryInformer,
                ConfigMap.class, secondaryInformer));
        TestInformerFactory rootFactory = new TestInformerFactory(namespaceFactory);
        Store<EchoResource> primaryStore = mock(Store.class);
        Reconciler<EchoResource> reconciler = mock(Reconciler.class);

        EchoResource primary = echoResource("default", "echo-sample");
        ConfigMap secondary = configMapWithLabel("default", "echo-config", "echo-sample");

        ControllerRegistration<EchoResource> registration = ControllerBuilder.forResource(EchoResource.class)
                .withReconciler(reconciler)
                .watches("configmaps", ConfigMap.class, Mappers.byLabel("echo-name"))
                .build();

        when(client.informers()).thenReturn(rootFactory);
        when(primaryInformer.addEventHandler(any())).thenReturn(primaryInformer);
        when(secondaryInformer.addEventHandler(any())).thenReturn(secondaryInformer);
        when(primaryInformer.getStore()).thenReturn(primaryStore);
        when(primaryStore.getByKey("default/echo-sample")).thenReturn(primary);
        when(reconciler.reconcile(any(), same(primary))).thenReturn(Result.done());

        ArgumentCaptor<ResourceEventHandler<ConfigMap>> secondaryHandlerCaptor =
                ArgumentCaptor.forClass(ResourceEventHandler.class);

        try (Operator operator = new Operator(client)) {
            operator.withNamespace("default").withShutdownHookEnabled(false);
            operator.register(registration);
            operator.start();

            verify(secondaryInformer).addEventHandler(secondaryHandlerCaptor.capture());

            secondaryHandlerCaptor.getValue().onAdd(secondary);

            ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
            verify(reconciler, timeout(2_000)).reconcile(requestCaptor.capture(), same(primary));

            Request request = requestCaptor.getValue();
            assertEquals("default", request.namespace());
            assertEquals("echo-sample", request.name());
            assertFalse(request.triggeredByPrimary());
            assertEquals(1, request.triggers().size());

            Trigger trigger = request.triggers().get(0);
            assertEquals(ResourceEventType.ADD, trigger.eventType());
            assertEquals(TriggerRole.SECONDARY, trigger.role());
            assertEquals("ConfigMap", trigger.kind());
            assertEquals("default", trigger.namespace());
            assertEquals("echo-config", trigger.name());
        }
    }

    private static EchoResource echoResource(String namespace, String name) {
        EchoResource resource = new EchoResource();
        resource.setApiVersion("example.com/v1alpha2");
        resource.setKind("EchoResource");
        resource.setMetadata(new ObjectMetaBuilder()
                .withNamespace(namespace)
                .withName(name)
                .withUid("uid-" + name)
                .build());
        return resource;
    }

    private static ConfigMap configMapWithLabel(String namespace, String name, String echoName) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                .withNamespace(namespace)
                .withName(name)
                .withLabels(Map.of("echo-name", echoName))
                .endMetadata()
                .build();
    }

    private static final class TestInformerFactory implements SharedInformerFactory {
        private final Map<Class<?>, SharedIndexInformer<?>> informers;
        private final TestInformerFactory namespaceFactory;
        private String requestedNamespace;
        private boolean started;
        private boolean stopped;

        private TestInformerFactory(Map<Class<?>, SharedIndexInformer<?>> informers) {
            this.informers = new HashMap<>(informers);
            this.namespaceFactory = null;
        }

        private TestInformerFactory(TestInformerFactory namespaceFactory) {
            this.informers = Map.of();
            this.namespaceFactory = namespaceFactory;
        }

        @Override
        public SharedInformerFactory inNamespace(String namespace) {
            this.requestedNamespace = namespace;
            return namespaceFactory;
        }

        @Override
        public SharedInformerFactory withName(String name) {
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends io.fabric8.kubernetes.api.model.HasMetadata> SharedIndexInformer<T> sharedIndexInformerFor(
                Class<T> apiTypeClass, long resyncPeriodInMillis) {
            return (SharedIndexInformer<T>) informers.get(apiTypeClass);
        }

        @Override
        public <T> SharedIndexInformer<T> getExistingSharedIndexInformer(Class<T> apiTypeClass) {
            return null;
        }

        @Override
        public Future<Void> startAllRegisteredInformers() {
            this.started = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void stopAllRegisteredInformers() {
            this.stopped = true;
        }

        @Override
        public void addSharedInformerEventListener(SharedInformerEventListener listener) {
        }
    }
}
