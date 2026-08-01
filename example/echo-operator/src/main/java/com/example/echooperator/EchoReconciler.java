package com.example.echooperator;

import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Echoes {@code data.message} of labeled ConfigMaps into an owned {@code <name>-echo} child.
 * The child is garbage-collected by Kubernetes when its owner is deleted.
 */
@Component
public class EchoReconciler implements Reconciler<ConfigMap> {
    static final String ENABLED_LABEL = "echo.example.com/enabled";
    static final String MESSAGE_KEY = "message";
    private static final String CHILD_SUFFIX = "-echo";
    static final String INDEX_ECHO_TARGET = "echo-target";

    private final KubernetesClient client;
    private final KubernetesEventPublisher events;

    EchoReconciler(KubernetesClient client, KubernetesEventPublisher events) {
        this.client = client;
        this.events = events;
    }

    @Override
    public ReconcileResult reconcile(ConfigMap resource, ReconciliationContext<ConfigMap> context) {
        if (isDelete(context) || !isEnabled(resource)) {
            return ReconcileResult.done();
        }
        var echo = echoOf(resource);
        var echoName = echo.getMetadata().getName();
        // ADR-0002: resolve this source via the primary cache's echo-target index before
        // writing — confirms the indexer is populated. Skipped when the context carries
        // no cache (unit tests); the runtime always supplies a synced one.
        if (context.cache() != null
                && context.cache().byIndex(INDEX_ECHO_TARGET, echoName).isEmpty()) {
            return ReconcileResult.requeueAfter(Duration.ofSeconds(1));
        }
        var namespace = resource.getMetadata().getNamespace();
        // The child is same-type but unlabeled, so the label-filtered primary cache never holds
        // it; the owns() informer (cacheFor) is unfiltered and does — no server round-trip.
        // Live read only for cache-less unit-test contexts.
        var existing = context.cache() != null
                ? context.cacheFor(ConfigMap.class).getByKey(namespace + "/" + echoName)
                : client.configMaps().inNamespace(namespace).withName(echoName).get();
        if (existing != null && Objects.equals(existing.getData(), echo.getData())) {
            return ReconcileResult.done();
        }
        if (existing == null) {
            client.configMaps().inNamespace(namespace).resource(echo).create();
        } else {
            client.configMaps().inNamespace(namespace).resource(echo).update();
        }
        events.normal(resource, "Echoed",
                "Echoed message into ConfigMap " + echo.getMetadata().getName());
        return ReconcileResult.done();
    }

    static boolean isEnabled(ConfigMap resource) {
        var labels = resource.getMetadata().getLabels();
        return labels != null && "true".equals(labels.get(ENABLED_LABEL));
    }

    static String message(ConfigMap resource) {
        var data = resource.getData();
        return data == null ? null : data.get(MESSAGE_KEY);
    }

    static String echoTargetName(ConfigMap source) {
        return source.getMetadata().getName() + CHILD_SUFFIX;
    }

    private boolean isDelete(ReconciliationContext<ConfigMap> context) {
        return context.triggers().stream()
                .anyMatch(trigger -> trigger.eventType() == ResourceEventType.DELETED);
    }

    private ConfigMap echoOf(ConfigMap source) {
        var metadata = source.getMetadata();
        return new ConfigMapBuilder()
                .withNewMetadata()
                .withName(metadata.getName() + CHILD_SUFFIX)
                .withNamespace(metadata.getNamespace())
                .withLabels(Map.of("app.kubernetes.io/managed-by", "echo-operator"))
                .withOwnerReferences(new OwnerReferenceBuilder()
                        .withApiVersion("v1").withKind("ConfigMap")
                        .withName(metadata.getName()).withUid(metadata.getUid())
                        .withController(true).withBlockOwnerDeletion(true).build())
                .endMetadata()
                .addToData(MESSAGE_KEY, message(source).toUpperCase(Locale.ROOT))
                .build();
    }
}
