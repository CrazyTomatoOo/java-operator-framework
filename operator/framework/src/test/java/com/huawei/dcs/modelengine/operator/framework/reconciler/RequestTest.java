package com.huawei.dcs.modelengine.operator.framework.reconciler;

import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestTest {

    @Test
    void shouldExposeEmptyTriggersFromExistingConstructor() {
        Request request = new Request("demo", "sample");

        assertEquals("demo", request.namespace());
        assertEquals("sample", request.name());
        assertEquals(List.of(), request.triggers());
        assertEquals(Optional.empty(), request.trigger());
        assertFalse(request.triggeredByPrimary());
        assertThrows(UnsupportedOperationException.class, () -> request.triggers().add(trigger(TriggerRole.PRIMARY)));
    }

    @Test
    void shouldExposeSingleTriggerConstructorAndPrimaryFlag() {
        Trigger primaryTrigger = trigger(TriggerRole.PRIMARY);

        Request request = new Request("demo", "sample", primaryTrigger);

        assertEquals(List.of(primaryTrigger), request.triggers());
        assertEquals(Optional.of(primaryTrigger), request.trigger());
        assertTrue(request.triggeredByPrimary());
    }

    @Test
    void shouldExposeMultipleTriggersConstructor() {
        Trigger primaryTrigger = trigger(TriggerRole.PRIMARY);
        Trigger secondaryTrigger = trigger(TriggerRole.SECONDARY);

        Request request = new Request("demo", "sample", List.of(primaryTrigger, secondaryTrigger));

        assertEquals(List.of(primaryTrigger, secondaryTrigger), request.triggers());
        assertEquals(Optional.of(primaryTrigger), request.trigger());
        assertTrue(request.triggeredByPrimary());
    }

    @Test
    void shouldKeepEqualityAndHashCodeByNamespaceAndNameOnly() {
        Request primaryRequest = new Request("demo", "sample", trigger(TriggerRole.PRIMARY));
        Request secondaryRequest = new Request("demo", "sample", trigger(TriggerRole.SECONDARY));

        assertEquals(primaryRequest, secondaryRequest);
        assertEquals(primaryRequest.hashCode(), secondaryRequest.hashCode());
    }

    @Test
    void shouldAppendTriggerWithoutMutatingOriginalRequest() {
        Request request = new Request("demo", "sample");
        Trigger secondaryTrigger = trigger(TriggerRole.SECONDARY);

        Request updated = request.withTrigger(secondaryTrigger);

        assertEquals(List.of(), request.triggers());
        assertEquals(List.of(secondaryTrigger), updated.triggers());
        assertEquals(Optional.of(secondaryTrigger), updated.trigger());
        assertFalse(updated.triggeredByPrimary());
    }

    private static Trigger trigger(TriggerRole role) {
        return new Trigger(
                ResourceEventType.ADD,
                "v1",
                "ConfigMap",
                "demo",
                "sample",
                "uid-" + role.name().toLowerCase(),
                role);
    }
}
