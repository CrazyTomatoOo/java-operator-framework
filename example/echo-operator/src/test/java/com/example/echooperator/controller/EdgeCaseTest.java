package com.example.echooperator.controller;

import com.example.echooperator.api.v1alpha2.EchoResource;

import com.example.echooperator.api.v1alpha2.EchoSpec;

import com.example.echooperator.api.v1alpha2.EchoStatus;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import com.huawei.dcs.modelengine.operator.framework.util.FinalizerHelper;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EdgeCaseTest {

    @Test
    void emptySpecWithFinalizerReturnsError() {
        KubernetesClient client = mockClient();
        EchoResource echo = new EchoResource();
        echo.setMetadata(new ObjectMetaBuilder()
                .withName("echo-empty")
                .withNamespace("default")
                .withUid("uid-empty")
                .build());
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client).reconcile(new Request("default", "echo-empty"), echo);

        assertNotNull(result.error(), "Expected reconcile to fail with empty spec");
        assertTrue(result.error() instanceof NullPointerException,
                "Expected NullPointerException due to missing spec");
    }

    @Test
    void rapidReconcileIsStable() {
        KubernetesClient client = mockClient();
        EchoResource echo = echoResource(1, "rapid");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);
        EchoReconciler reconciler = new EchoReconciler(client);

        for (int i = 0; i < 100; i++) {
            Result result = reconciler.reconcile(new Request("default", "echo-sample"), echo);
            assertNull(result.error(), "Unexpected error on reconcile iteration " + i);
            assertFalse(result.requeue(), "Unexpected requeue on reconcile iteration " + i);
        }

        assertEquals("READY", echo.getStatus().phase);
        assertEquals("rapid", echo.getStatus().message);
    }

    @Test
    void zeroReplicasIsAllowed() {
        KubernetesClient client = mockClient();
        EchoResource echo = echoResource(0, "zero");
        FinalizerHelper.addFinalizer(echo, EchoReconciler.FINALIZER);

        Result result = new EchoReconciler(client).reconcile(new Request("default", "echo-sample"), echo);

        assertNull(result.error());
        assertEquals("READY", echo.getStatus().phase);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KubernetesClient mockClient() {
        KubernetesClient client = mock(KubernetesClient.class);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = mock(MixedOperation.class);
        RollableScalableResource<Deployment> deploymentResource = mock(RollableScalableResource.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> services = mock(MixedOperation.class);
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        io.fabric8.kubernetes.client.dsl.NamespaceableResource<EchoResource> echoResource = mock(io.fabric8.kubernetes.client.dsl.NamespaceableResource.class);
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
}
