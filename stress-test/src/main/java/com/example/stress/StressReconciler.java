package com.example.stress;

import com.example.stress.api.v1alpha1.StressTestResource;
import com.example.stress.api.v1alpha1.StressTestSpec;
import com.example.stress.api.v1alpha1.StressTestStatus;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import com.huawei.dcs.modelengine.operator.framework.util.OwnerReferenceHelper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Reconciler used by the stress test. In "noop" mode it only records metrics. In
 * "crud" mode it simulates a real controller: for every event it queries the child
 * ConfigMap from the API server, creates it when absent, replaces it when drifted,
 * deletes it on a churn cadence, and writes the CR status back — all against the API
 * server, competing with the load generator for the same API budget.
 * <p>
 * Status writebacks trigger self-inflicted watch events; reconciles whose status
 * already observed the current spec.seq are counted as echoes and short-circuit
 * (the standard idempotent-reconcile pattern), avoiding an update loop.
 */
public final class StressReconciler implements Reconciler<StressTestResource> {

    private final KubernetesClient client;
    private final StressConfig config;
    private final StressMetrics metrics;
    private final boolean crudMode;
    private final ConcurrentHashMap<String, Long> lastSeqByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastChurnSeqByKey = new ConcurrentHashMap<>();

    public StressReconciler(KubernetesClient client, StressConfig config, StressMetrics metrics) {
        this.client = client;
        this.config = config;
        this.metrics = metrics;
        this.crudMode = "crud".equals(config.reconcileMode);
    }

    @Override
    public Result reconcile(Request request, StressTestResource resource) {
        long startedAt = System.currentTimeMillis();
        StressTestSpec spec = resource.getSpec();
        if (spec == null) {
            metrics.reconciles.increment();
            return Result.done();
        }
        if (crudMode && isEcho(resource, spec)) {
            metrics.echoReconciles.increment();
            return Result.done();
        }
        if (spec.sentAtMs > 0 && startedAt >= spec.sentAtMs) {
            metrics.recordLatency(startedAt, startedAt - spec.sentAtMs);
        }
        String key = request.namespace() + "/" + request.name();
        Long previous = lastSeqByKey.put(key, spec.seq);
        if (previous != null && spec.seq > previous + 1) {
            metrics.coalesced.add(spec.seq - previous - 1);
        }
        metrics.reconciles.increment();
        if (crudMode) {
            reconcileChild(resource, key, spec);
        }
        if (config.reconcileWorkMs > 0) {
            try {
                Thread.sleep(config.reconcileWorkMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        return Result.done();
    }

    /** Self-triggered status event: status already observed this spec version. */
    private boolean isEcho(StressTestResource resource, StressTestSpec spec) {
        StressTestStatus status = resource.getStatus();
        return status != null && status.observedSeq == spec.seq;
    }

    private void reconcileChild(StressTestResource resource, String key, StressTestSpec spec) {
        String namespace = resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        String childName = name + "-child";
        String desiredSeq = Long.toString(spec.seq);
        try {
            ConfigMap child = client.configMaps().inNamespace(namespace).withName(childName).get();
            metrics.apiReads.increment();
            if (child == null) {
                createChild(resource, namespace, childName, desiredSeq, spec.payload);
            } else if (shouldChurn(key, spec.seq)) {
                client.configMaps().inNamespace(namespace).withName(childName).delete();
                metrics.apiDeletes.increment();
            } else {
                String actualSeq = child.getData() == null ? null : child.getData().get("seq");
                if (!desiredSeq.equals(actualSeq)) {
                    child.getData().put("seq", desiredSeq);
                    client.configMaps().inNamespace(namespace).resource(child).replace();
                    metrics.apiUpdates.increment();
                }
            }
            writeStatus(namespace, name, spec.seq);
        } catch (KubernetesClientException exception) {
            metrics.apiErrors.increment();
        }
    }

    private void createChild(StressTestResource owner, String namespace, String childName,
            String desiredSeq, String payload) {
        ConfigMap child = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(childName)
                .withNamespace(namespace)
                .withOwnerReferences(OwnerReferenceHelper.createControllerOwnerReference(owner))
                .endMetadata()
                .addToData("seq", desiredSeq)
                .addToData("payload", payload == null ? "" : payload)
                .build();
        client.configMaps().inNamespace(namespace).resource(child).create();
        metrics.apiCreates.increment();
    }

    /** Once per key per churn interval: delete the child so the next event recreates it. */
    private boolean shouldChurn(String key, long seq) {
        if (config.childChurn <= 0 || seq <= 0 || seq % config.childChurn != 0) {
            return false;
        }
        Long previous = lastChurnSeqByKey.put(key, seq);
        return previous == null || previous < seq;
    }

    private void writeStatus(String namespace, String name, long seq) {
        String patch = "{\"status\":{\"observedSeq\":" + seq
                + ",\"phase\":\"Ready\",\"lastReconcileMs\":" + System.currentTimeMillis() + "}}";
        client.resources(StressTestResource.class).inNamespace(namespace).withName(name)
                .subresource("status")
                .patch(PatchContext.of(PatchType.JSON_MERGE), patch);
        metrics.apiStatusWrites.increment();
    }
}
