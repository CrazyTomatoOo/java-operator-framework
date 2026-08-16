/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationTrigger;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.TriggerRole;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.impl.cache.CacheImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Drives {@link GreetingReconciler} through its three phases with a mocked client, asserting
 * the requests the reconciler issues: the finalizer JSON patch, the server-side apply of the
 * child, the status subresource merge patch, and the external-state ConfigMap lifecycle.
 *
 * <p>The fabric8 in-memory mock cannot round-trip custom-kind responses on the polymorphic
 * {@code client.resource(...).patch(...)} path (see {@code GreetingSsaWireTest}), so these
 * tests verify request shape rather than server semantics; the real-cluster e2e verifies the
 * converged end state.
 */
class GreetingReconcilerTest {
    private static final String NAMESPACE = "ns";
    private static final String NAME = "greet-1";

    private KubernetesClient client;
    private KubernetesEventPublisher events;
    private GreetingReconciler reconciler;

    private NamespaceableResource<Greeting> greetingResource;
    private Resource<Greeting> statusResource;
    private NamespaceableResource<ConfigMap> applyResource;
    private MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMaps;
    private MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> scoped;
    private Resource<ConfigMap> named;
    private NamespaceableResource<ConfigMap> desiredResource;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(KubernetesClient.class);
        events = mock(KubernetesEventPublisher.class);
        reconciler = new GreetingReconciler(client, events, new GreetingConfigMap());

        greetingResource = mock(NamespaceableResource.class);
        statusResource = mock(Resource.class);
        applyResource = mock(NamespaceableResource.class);
        configMaps = mock(MixedOperation.class);
        scoped = mock(MixedOperation.class);
        named = mock(Resource.class);
        desiredResource = mock(NamespaceableResource.class);

        when(client.resource(any(Greeting.class))).thenReturn(greetingResource);
        when(client.resource(any(ConfigMap.class))).thenReturn(applyResource);
        when(applyResource.fieldManager(GreetingReconciler.FIELD_MANAGER)).thenReturn(applyResource);
        when(greetingResource.subresource("status")).thenReturn(statusResource);

        when(client.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace(NAMESPACE)).thenReturn(scoped);
        when(scoped.withName(anyString())).thenReturn(named);
        when(scoped.resource(any(ConfigMap.class))).thenReturn(desiredResource);
    }

    @Test
    void addsFinalizerAndRequeuesImmediatelyWhenAbsent() throws Exception {
        var resource = greeting(false, false);
        when(greetingResource.patch(any(), anyString())).thenReturn(greeting(true, false));

        var result = reconciler.reconcile(resource, context(resource, cacheWithChild()));

        assertThat(result.requeueDelay()).hasValue(Duration.ZERO);
        var body = captureLastPatchBody(greetingResource);
        assertThat(body).contains("greetings.example.com/finalizer");
        verify(events, never()).normal(any(), anyString(), anyString());
        verify(events, never()).warning(any(), anyString(), anyString());
    }

    @Test
    void appliesChildAndPersistsStatusOnRender() throws Exception {
        var resource = greeting(true, false);
        when(greetingResource.patch(any(), anyString())).thenReturn(resource);
        when(applyResource.serverSideApply()).thenReturn(childWithMessage("hello"));
        when(named.get()).thenReturn(null);
        when(desiredResource.create()).thenReturn(new ConfigMapBuilder()
                .withNewMetadata().withName(NAME + "-external").withNamespace(NAMESPACE).endMetadata().build());
        when(statusResource.patch(any(), anyString())).thenReturn(resource);

        var result = reconciler.reconcile(resource, context(resource, cacheWithChild()));

        assertThat(result.isDone()).isTrue();
        assertThat(renderedChild(resource).getData()).containsEntry("message", "hello");
        var statusBody = captureLastPatchBody(statusResource);
        assertThat(statusBody).contains("\"observedGeneration\"");
        assertThat(statusBody).contains("\"phase\":\"Rendered\"");
        assertThat(statusBody).contains("\"message\":\"hello\"");
        verify(events).normal(resource, "Rendered",
                "Rendered message into ConfigMap greet-1-child, external state synchronized");
    }

    @Test
    void requeuesNowWhileChildIsInvisibleInOwnedCache() throws Exception {
        var resource = greeting(true, false);
        when(greetingResource.patch(any(), anyString())).thenReturn(resource);
        when(applyResource.serverSideApply()).thenReturn(childWithMessage("hello"));

        var result = reconciler.reconcile(resource, context(resource, new CacheImpl<>()));

        assertThat(result.requeueDelay()).hasValue(Duration.ZERO);
        verify(named, never()).get();
        verify(events, never()).normal(any(), anyString(), anyString());
    }

    @Test
    void rendersStyledMessageFromWatchedConfigMapCache() throws Exception {
        var resource = greeting(true, false);
        resource.getSpec().setStyle("fancy");
        when(greetingResource.patch(any(), anyString())).thenReturn(resource);
        when(applyResource.serverSideApply()).thenReturn(childWithMessage("hello"));
        when(named.get()).thenReturn(null);
        when(desiredResource.create()).thenReturn(new ConfigMapBuilder()
                .withNewMetadata().withName(NAME + "-external").withNamespace(NAMESPACE).endMetadata().build());
        when(statusResource.patch(any(), anyString())).thenReturn(resource);

        reconciler.reconcile(resource, context(resource, cacheWithChild()));

        assertThat(renderedChild(resource).getData()).containsEntry("message", "» hello");
    }

    @Test
    void cleansExternalStateAndRemovesFinalizerOnDeletion() throws Exception {
        var resource = greeting(true, true);
        when(named.get()).thenReturn(new ConfigMapBuilder()
                .withNewMetadata().withName(NAME + "-external").withNamespace(NAMESPACE).endMetadata()
                .addToData("message", "hello").build());
        when(named.delete()).thenReturn(List.of());
        when(greetingResource.patch(any(), anyString())).thenReturn(greeting(false, true));

        var result = reconciler.reconcile(resource, context(resource, cacheWithChild()));

        assertThat(result.isDone()).isTrue();
        verify(named).delete();
        verify(events).normal(resource, "Cleaned", "Deleted external state ConfigMap greet-1-external");
        var body = captureLastPatchBody(greetingResource);
        assertThat(body).contains("/metadata/finalizers").contains("\"remove\"");
    }

    @Test
    void skipsCleanupWhenExternalStateIsAlreadyGone() throws Exception {
        var resource = greeting(true, true);
        when(named.get()).thenReturn(null);
        when(greetingResource.patch(any(), anyString())).thenReturn(greeting(false, true));

        var result = reconciler.reconcile(resource, context(resource, cacheWithChild()));

        assertThat(result.isDone()).isTrue();
        verify(named, never()).delete();
        verify(events, never()).normal(any(), anyString(), anyString());
    }

    @Test
    void propagatesCallbackExceptionsForTheFrameworkRetryPolicy() {
        var resource = greeting(true, false);
        when(greetingResource.patch(any(), anyString())).thenReturn(resource);
        when(applyResource.serverSideApply()).thenThrow(new KubernetesClientException("apply rejected"));

        assertThatThrownBy(() -> reconciler.reconcile(resource, context(resource, cacheWithChild())))
                .isInstanceOf(KubernetesClientException.class)
                .hasMessageContaining("apply rejected");
    }

    private Greeting greeting(boolean withFinalizer, boolean deleting) {
        var greeting = new Greeting();
        var metadata = new ObjectMetaBuilder()
                .withName(NAME)
                .withNamespace(NAMESPACE)
                .withUid("uid-1")
                .withGeneration(2L)
                .build();
        if (withFinalizer) {
            metadata.setFinalizers(List.of(GreetingReconciler.FINALIZER));
        }
        if (deleting) {
            metadata.setDeletionTimestamp("2026-08-16T00:00:00Z");
        }
        greeting.setMetadata(metadata);
        var spec = new GreetingSpec();
        spec.setMessage("hello");
        greeting.setSpec(spec);
        return greeting;
    }

    private static ConfigMap renderedChild(Greeting primary) {
        return new GreetingConfigMap().desired(primary, context(primary, cacheWithChild()));
    }

    private static CacheImpl<ConfigMap> cacheWithChild() {
        var cache = new CacheImpl<ConfigMap>();
        cache.put(configMap(NAME + "-child"));
        cache.put(styles("fancy", "» "));
        return cache;
    }

    private static ConfigMap configMap(String name) {
        return new ConfigMapBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .addToData("message", "hello")
                .build();
    }

    private static ConfigMap styles(String name, String prefix) {
        return new ConfigMapBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .addToData("prefix", prefix)
                .build();
    }

    private static ReconciliationContext<Greeting> context(Greeting resource, CacheImpl<ConfigMap> cmCache) {
        var primaryCache = new CacheImpl<Greeting>();
        primaryCache.put(resource);
        var reference = ResourceReference.from(resource);
        var trigger = new ReconciliationTrigger(ResourceEventType.UPDATED, TriggerRole.PRIMARY, reference);
        return new ReconciliationContext<>(reference.key(), List.of(trigger), primaryCache,
                Map.of(Greeting.class, primaryCache, ConfigMap.class, cmCache));
    }

    private static String captureLastPatchBody(Resource<?> target) throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(target, atLeastOnce()).patch(any(), captor.capture());
        var values = captor.getAllValues();
        return values.get(values.size() - 1);
    }

    private static ConfigMap childWithMessage(String message) {
        return new ConfigMapBuilder()
                .withNewMetadata().withName(NAME + "-child").withNamespace(NAMESPACE).endMetadata()
                .addToData("message", message)
                .build();
    }
}