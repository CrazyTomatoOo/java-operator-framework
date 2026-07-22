package com.example.echooperator.controller;

import com.example.echooperator.api.v1alpha2.EchoResource;

import com.example.echooperator.api.v1alpha2.EchoSpec;

import com.example.echooperator.api.v1alpha2.EchoStatus;

import com.huawei.dcs.modelengine.operator.framework.event.EventRecorder;
import com.huawei.dcs.modelengine.operator.framework.util.FinalizerHelper;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NamespaceableResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EchoReconcilerTest {

    @Test
    void testCreateDeploymentAndService() {
        KubernetesClient client = mockClient();
        EchoResource echo = echoResource(2, "hello");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client).reconcile(new Request("default", "echo-sample"), echo);

        assertNull(result.error());
        assertFalse(result.requeue());
        assertEquals("READY", echo.getStatus().phase);
        assertEquals("hello", echo.getStatus().message);

        ArgumentCaptor<Deployment> deploymentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(client.apps().deployments()).resource(deploymentCaptor.capture());
        Deployment deployment = deploymentCaptor.getValue();
        assertEquals("echo-sample", deployment.getMetadata().getName());
        assertEquals("default", deployment.getMetadata().getNamespace());
        assertEquals("echo", deployment.getMetadata().getLabels().get("app"));
        assertEquals("echo-operator", deployment.getMetadata().getLabels().get("managed-by"));
        assertEquals(2, deployment.getSpec().getReplicas());
        assertEquals("echo", deployment.getSpec().getSelector().getMatchLabels().get("app"));
        assertEquals("nginx:alpine", deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertEquals(80, deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getPorts().get(0).getContainerPort());
        assertOwnerReference(deployment.getMetadata().getOwnerReferences().get(0));

        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        verify(client.services()).resource(serviceCaptor.capture());
        Service service = serviceCaptor.getValue();
        assertEquals("echo-sample", service.getMetadata().getName());
        assertEquals("default", service.getMetadata().getNamespace());
        assertEquals("echo", service.getSpec().getSelector().get("app"));
        assertEquals(80, service.getSpec().getPorts().get(0).getPort());
        assertEquals(80, service.getSpec().getPorts().get(0).getTargetPort().getIntVal());
        assertOwnerReference(service.getMetadata().getOwnerReferences().get(0));

        verify(client.resources(EchoResource.class).resource(echo)).updateStatus();
    }

    @Test
    void testInvalidReplicas() {
        KubernetesClient client = mockClient();
        EchoResource echo = echoResource(-1, "hello");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client).reconcile(new Request("default", "echo-sample"), echo);

        assertFalse(result.requeue());
        assertNull(result.error());
        assertEquals("FAILED", echo.getStatus().phase);
        assertEquals("replicas must be >= 0", echo.getStatus().message);
        verify(client.apps().deployments(), never()).resource(org.mockito.ArgumentMatchers.any(Deployment.class));
        verify(client.services(), never()).resource(org.mockito.ArgumentMatchers.any(Service.class));
        verify(client.resources(EchoResource.class).resource(echo)).updateStatus();
    }

    @Test
    void testFinalizer() {
        KubernetesClient client = mockClient();
        EchoResource echo = echoResource(1, "hello");

        Result addResult = new EchoReconciler(client).reconcile(new Request("default", "echo-sample"), echo);

        assertTrue(addResult.requeue());
        assertNull(addResult.error());
        assertTrue(FinalizerHelper.hasFinalizer(echo, EchoReconciler.FINALIZER));
        verify(client.apps().deployments(), never()).resource(any(Deployment.class));
        verify(client.services(), never()).resource(any(Service.class));

        echo.getMetadata().setDeletionTimestamp("2026-06-18T08:00:00Z");

        Result deleteResult = new EchoReconciler(client).reconcile(new Request("default", "echo-sample"), echo);

        assertFalse(deleteResult.requeue());
        assertNull(deleteResult.error());
        assertFalse(FinalizerHelper.hasFinalizer(echo, EchoReconciler.FINALIZER));
    }

    @Test
    void testStatusUpdate() {
        KubernetesClient client = mockClient();
        EchoResource echo = echoResource(1, "status-message");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client).reconcile(new Request("default", "echo-sample"), echo);

        assertFalse(result.requeue());
        assertNull(result.error());
        EchoStatus status = echo.getStatus();
        assertNotNull(status);
        assertEquals("READY", status.phase);
        assertEquals("status-message", status.message);
        verify(client.resources(EchoResource.class).resource(echo)).updateStatus();
    }

    @Test
    void testRetryOnTransientFailure() {
        RuntimeException failure = new RuntimeException("temporary status failure");
        KubernetesClient client = mockClient(failure);
        EchoResource echo = echoResource(1, "hello");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client).reconcile(new Request("default", "echo-sample"), echo);

        assertTrue(result.requeue());
        assertSame(failure, result.error());
    }

    @Test
    void testZeroReplicas() {
        KubernetesClient client = mockClient();
        EchoResource echo = echoResource(0, "zero");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client).reconcile(new Request("default", "echo-sample"), echo);

        assertNull(result.error());
        assertFalse(result.requeue());
        assertEquals("READY", echo.getStatus().phase);

        ArgumentCaptor<Deployment> deploymentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(client.apps().deployments()).resource(deploymentCaptor.capture());
        assertEquals(0, deploymentCaptor.getValue().getSpec().getReplicas());
    }

    @Test
    void testMetricsRecorded() {
        KubernetesClient client = mockClient();
        MeterRegistry registry = mock(MeterRegistry.class);
        Counter counter = mock(Counter.class);
        when(registry.counter("echo_reconcile_total", "namespace", "default")).thenReturn(counter);
        EchoResource echo = echoResource(1, "hello");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        new EchoReconciler(client, registry).reconcile(new Request("default", "echo-sample"), echo);

        verify(registry).counter("echo_reconcile_total", "namespace", "default");
        verify(counter).increment();
    }

    @Test
    void testRecordsNormalEventOnSuccessfulReconcile() {
        KubernetesClient client = mockClient();
        EventRecorder recorder = mock(EventRecorder.class);
        EchoResource echo = echoResource(1, "hello");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client, null, recorder).reconcile(new Request("default", "echo-sample"), echo);

        assertNull(result.error());
        assertFalse(result.requeue());
        verify(recorder).normal(echo, "Reconciled", "Echo resource reconciled");
    }

    @Test
    void testRecordsWarningEventOnReconcileFailure() {
        RuntimeException failure = new RuntimeException("temporary status failure");
        KubernetesClient client = mockClient(failure);
        EventRecorder recorder = mock(EventRecorder.class);
        EchoResource echo = echoResource(1, "hello");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client, null, recorder).reconcile(new Request("default", "echo-sample"), echo);

        assertSame(failure, result.error());
        verify(recorder).warning(echo, "ReconcileFailed", "temporary status failure");
    }

    @Test
    void testRecordsWarningEventWithExceptionClassWhenMessageIsNull() {
        RuntimeException failure = new RuntimeException();
        KubernetesClient client = mockClient(failure);
        EventRecorder recorder = mock(EventRecorder.class);
        EchoResource echo = echoResource(1, "hello");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client, null, recorder).reconcile(new Request("default", "echo-sample"), echo);

        assertSame(failure, result.error());
        verify(recorder).warning(echo, "ReconcileFailed", "RuntimeException");
    }

    @Test
    void testRecorderFailureDoesNotChangeSuccessfulReconcileResult() {
        KubernetesClient client = mockClient();
        EventRecorder recorder = mock(EventRecorder.class);
        doThrow(new RuntimeException("recorder down")).when(recorder).normal(any(), anyString(), anyString());
        EchoResource echo = echoResource(1, "hello");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client, null, recorder).reconcile(new Request("default", "echo-sample"), echo);

        assertNull(result.error());
        assertEquals("READY", echo.getStatus().phase);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KubernetesClient mockClient() {
        return mockClient(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KubernetesClient mockDeepStubClient(RuntimeException deploymentFailure) {
        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = mock(MixedOperation.class);
        RollableScalableResource<Deployment> deploymentResource = mock(RollableScalableResource.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> services = mock(MixedOperation.class);
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        MixedOperation<EchoResource, KubernetesResourceList<EchoResource>, Resource<EchoResource>> echoResources = mock(MixedOperation.class);
        Resource<EchoResource> statusResource = mock(Resource.class);

        when(client.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(deployments);
        when(deployments.resource(any(Deployment.class))).thenReturn(deploymentResource);
        when(client.services()).thenReturn(services);
        when(services.resource(any(Service.class))).thenReturn(serviceResource);
        when(client.resources(EchoResource.class)).thenReturn(echoResources);
        when(echoResources.resource(any(EchoResource.class))).thenReturn(statusResource);
        if (deploymentFailure != null) {
            when(deploymentResource.createOrReplace()).thenThrow(deploymentFailure);
        }
        return client;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KubernetesClient mockClient(RuntimeException statusFailure) {
        KubernetesClient client = mock(KubernetesClient.class);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = mock(MixedOperation.class);
        RollableScalableResource<Deployment> deploymentResource = mock(RollableScalableResource.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> services = mock(MixedOperation.class);
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        NamespaceableResource<EchoResource> echoResource = mock(NamespaceableResource.class);
        MixedOperation<EchoResource, KubernetesResourceList<EchoResource>, Resource<EchoResource>> echoResources = mock(MixedOperation.class);
        Resource<EchoResource> statusResource = mock(Resource.class);
        AtomicReference<EchoResource> editedResource = new AtomicReference<>();

        when(client.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(deployments);
        when(deployments.resource(any(Deployment.class))).thenReturn(deploymentResource);
        when(client.services()).thenReturn(services);
        when(services.resource(any(Service.class))).thenReturn(serviceResource);
        when(client.resource(any(EchoResource.class))).thenAnswer(invocation -> {
            editedResource.set(invocation.getArgument(0));
            return echoResource;
        });
        when(echoResource.edit(org.mockito.ArgumentMatchers.<UnaryOperator<EchoResource>>any()))
                .thenAnswer(invocation -> invocation.getArgument(0, UnaryOperator.class).apply(editedResource.get()));
        when(client.resources(EchoResource.class)).thenReturn(echoResources);
        when(echoResources.resource(any(EchoResource.class))).thenReturn(statusResource);
        if (statusFailure != null) {
            when(statusResource.updateStatus()).thenThrow(statusFailure);
        }
        return client;
    }

    private static EchoResource echoResource(int replicas, String message) {
        EchoSpec spec = new EchoSpec();
        spec.replicas = replicas;
        spec.message = message;

        EchoResource resource = new EchoResource();
        resource.setApiVersion("example.com/v1alpha1");
        resource.setKind("EchoResource");
        resource.setMetadata(new ObjectMetaBuilder()
                .withName("echo-sample")
                .withNamespace("default")
                .withUid("uid-123")
                .build());
        resource.setSpec(spec);
        return resource;
    }

    private static void assertOwnerReference(io.fabric8.kubernetes.api.model.OwnerReference ownerReference) {
        assertNotNull(ownerReference);
        assertEquals("example.com/v1alpha2", ownerReference.getApiVersion());
        assertEquals("EchoResource", ownerReference.getKind());
        assertEquals("echo-sample", ownerReference.getName());
        assertEquals("uid-123", ownerReference.getUid());
        assertEquals(Boolean.TRUE, ownerReference.getController());
        assertEquals(Boolean.TRUE, ownerReference.getBlockOwnerDeletion());
    }
}
