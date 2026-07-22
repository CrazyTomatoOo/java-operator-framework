package com.example.echooperator.controller;

import com.example.echooperator.api.v1alpha2.EchoResource;
import com.example.echooperator.api.v1alpha2.EchoSpec;
import com.example.echooperator.api.v1alpha2.EchoStatus;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger;
import com.huawei.dcs.modelengine.operator.framework.reconciler.TriggerRole;
import com.huawei.dcs.modelengine.operator.framework.event.EventRecorder;
import com.huawei.dcs.modelengine.operator.framework.util.FinalizerHelper;
import com.huawei.dcs.modelengine.operator.framework.util.OwnerReferenceHelper;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EchoReconciler implements Reconciler<EchoResource> {
    public static final String FINALIZER = "echo.example.com/finalizer";

    private static final Logger LOGGER = Logger.getLogger(EchoReconciler.class.getName());

    private static final Map<String, String> LABELS = Map.of(
            "app", "echo",
            "managed-by", "echo-operator");

    private final KubernetesClient client;
    private final MeterRegistry registry;
    private final EventRecorder eventRecorder;

    public EchoReconciler(KubernetesClient client) {
        this(client, null, null);
    }

    public EchoReconciler(KubernetesClient client, MeterRegistry registry) {
        this(client, registry, null);
    }

    public EchoReconciler(KubernetesClient client, MeterRegistry registry, EventRecorder eventRecorder) {
        this.client = client;
        this.registry = registry;
        this.eventRecorder = eventRecorder;
    }

    @Override
    public Result reconcile(Request request, EchoResource resource) {
        try {
            recordReconcile(request);

            // Example: detect whether a secondary ConfigMap event triggered this reconciliation.
            // for (Trigger trigger : request.triggers()) {
            //     if (trigger.role() == TriggerRole.SECONDARY && "ConfigMap".equals(trigger.kind())) {
            //         LOGGER.info(() -> "Reconciling due to ConfigMap event: " + trigger.name());
            //     }
            // }
            // boolean fromPrimary = request.triggeredByPrimary();

            if (resource.getMetadata().getDeletionTimestamp() != null) {
                LOGGER.info(() -> "Cleaning up Echo resource " + resource.getMetadata().getName());
                this.client.resource(resource).edit(r -> {
                    FinalizerHelper.removeFinalizer(r, FINALIZER);
                    return r;
                });
                return Result.done();
            }

            if (!FinalizerHelper.hasFinalizer(resource, FINALIZER)) {
                this.client.resource(resource).edit(r -> {
                    FinalizerHelper.addFinalizer(r, FINALIZER);
                    return r;
                });
                return Result.requeueNow();
            }

            EchoSpec spec = resource.getSpec();
            if (spec.replicas < 0) {
                updateStatus(resource, "FAILED", "replicas must be >= 0");
                return Result.done();
            }

            Deployment deployment = buildDeployment(resource, spec.replicas);
            Service service = buildService(resource);

            this.client.apps().deployments().resource(deployment).createOrReplace();
            this.client.services().resource(service).createOrReplace();

            updateStatus(resource, "READY", spec.message);
            recordEvent(() -> this.eventRecorder.normal(resource, "Reconciled", "Echo resource reconciled"));
            return Result.done();
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            recordEvent(() -> this.eventRecorder.warning(resource, "ReconcileFailed", message));
            recordError(request);
            return Result.error(exception);
        }
    }

    private void recordReconcile(Request request) {
        if (registry != null) {
            registry.counter("echo_reconcile_total", "namespace", request.namespace()).increment();
        }
    }

    private void recordError(Request request) {
        if (registry != null) {
            registry.counter("echo_reconcile_errors_total", "namespace", request.namespace()).increment();
        }
    }

    private void recordEvent(Runnable recorderCall) {
        if (eventRecorder == null) {
            return;
        }
        try {
            recorderCall.run();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Failed to record Kubernetes Event", exception);
        }
    }

    private Deployment buildDeployment(EchoResource resource, int replicas) {
        String name = resource.getMetadata().getName();
        String namespace = resource.getMetadata().getNamespace();
        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .withLabels(LABELS)
                .withOwnerReferences(OwnerReferenceHelper.createControllerOwnerReference(resource))
                .endMetadata()
                .withNewSpec()
                .withReplicas(replicas)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "echo"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(LABELS)
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("echo")
                .withImage("nginx:alpine")
                .addNewPort()
                .withContainerPort(80)
                .endPort()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private Service buildService(EchoResource resource) {
        String name = resource.getMetadata().getName();
        String namespace = resource.getMetadata().getNamespace();
        return new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .withLabels(LABELS)
                .withOwnerReferences(OwnerReferenceHelper.createControllerOwnerReference(resource))
                .endMetadata()
                .withNewSpec()
                .withSelector(Map.of("app", "echo"))
                .addNewPort()
                .withPort(80)
                .withNewTargetPort(80)
                .endPort()
                .endSpec()
                .build();
    }

    private void updateStatus(EchoResource resource, String phase, String message) {
        EchoStatus status = resource.getStatus();
        if (status == null) {
            status = new EchoStatus();
            resource.setStatus(status);
        }
        status.phase = phase;
        status.message = message;
        this.client.resources(EchoResource.class).resource(resource).updateStatus();
    }
}
