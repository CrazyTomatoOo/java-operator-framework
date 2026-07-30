package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcileApiTest {
    @Test
    void createsValidatedSchedulingResults() {
        assertTrue(ReconcileResult.done().isDone());
        assertEquals(Duration.ZERO, ReconcileResult.requeueNow().requeueDelay().orElseThrow());
        assertEquals(Duration.ofSeconds(5),
                ReconcileResult.requeueAfter(Duration.ofSeconds(5)).requeueDelay().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> ReconcileResult.requeueAfter(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ReconcileResult.requeueAfter(Duration.ofSeconds(-1)));
    }

    @Test
    void snapshotsReconciliationTriggers() {
        var resource = new ConfigMapBuilder()
                .withApiVersion("v1")
                .withKind("ConfigMap")
                .withNewMetadata()
                .withNamespace("operators")
                .withName("sample")
                .withUid("uid-1")
                .endMetadata()
                .build();
        var reference = ResourceReference.from(resource);
        var triggers = new ArrayList<ReconciliationTrigger>();
        triggers.add(new ReconciliationTrigger(ResourceEventType.ADDED, TriggerRole.PRIMARY, reference));

        var context = ReconciliationContext.withoutCache(reference.key(), triggers);
        triggers.clear();

        assertEquals(new ResourceKey("operators", "sample"), context.resourceKey());
        assertEquals(1, context.triggers().size());
        assertThrows(UnsupportedOperationException.class, () -> context.triggers().clear());
        assertFalse(ReconcileResult.requeueNow().isDone());
    }

    @Test
    void rejectsInvalidResourceIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceKey("", "name"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceKey("namespace", " "));
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceReference("", "ConfigMap", "default", "name", null));
    }
}
