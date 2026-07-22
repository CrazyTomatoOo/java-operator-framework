package com.huawei.dcs.modelengine.operator.framework.reconciler;

import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventType;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestTriggerTest {

    @Test
    void triggerShouldCaptureResourceMetadata() {
        ConfigMap resource = new ConfigMap();
        resource.setApiVersion("v1");
        resource.setKind("ConfigMap");
        resource.setMetadata(new ObjectMeta());
        resource.getMetadata().setNamespace("demo");
        resource.getMetadata().setName("sample");
        resource.getMetadata().setUid("uid-123");

        Trigger trigger = Trigger.from(resource, ResourceEventType.UPDATE, TriggerRole.SECONDARY);

        assertEquals(ResourceEventType.UPDATE, trigger.eventType());
        assertEquals("v1", trigger.apiVersion());
        assertEquals("ConfigMap", trigger.kind());
        assertEquals("demo", trigger.namespace());
        assertEquals("sample", trigger.name());
        assertEquals("uid-123", trigger.uid());
        assertEquals(TriggerRole.SECONDARY, trigger.role());
    }

    @Test
    void triggerShouldBeEqualWhenSameFields() {
        Trigger first = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.PRIMARY);
        Trigger second = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.PRIMARY);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void triggerShouldNotBeEqualWhenDifferent() {
        Trigger primary = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.PRIMARY);
        Trigger secondary = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.SECONDARY);
        Trigger update = new Trigger(ResourceEventType.UPDATE, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.PRIMARY);

        assertNotEquals(primary, secondary);
        assertNotEquals(primary, update);
        assertNotEquals(primary, null);
    }

    @Test
    void requestTriggeredByPrimaryWithPrimaryTrigger() {
        Trigger primary = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.PRIMARY);
        Request request = new Request("demo", "sample", primary);

        assertEquals(Optional.of(primary), request.trigger());
        assertTrue(request.triggeredByPrimary());
    }

    @Test
    void requestTriggeredByPrimaryWithSecondaryTrigger() {
        Trigger secondary = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.SECONDARY);
        Request request = new Request("demo", "sample", secondary);

        assertEquals(Optional.of(secondary), request.trigger());
        assertFalse(request.triggeredByPrimary());
    }

    @Test
    void requestTriggeredByPrimaryWithMultipleTriggers() {
        Trigger primary = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.PRIMARY);
        Trigger secondary = new Trigger(ResourceEventType.UPDATE, "v1", "ConfigMap", "demo", "sample", "uid-456", TriggerRole.SECONDARY);
        Request request = new Request("demo", "sample", List.of(primary, secondary));

        assertEquals(Optional.of(primary), request.trigger());
        assertTrue(request.triggeredByPrimary());
        assertEquals(List.of(primary, secondary), request.triggers());
    }

    @Test
    void requestWithSecondaryTriggerDoesNotTriggerPrimary() {
        Request request = new Request("demo", "sample");
        Trigger secondary = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.SECONDARY);
        Request updated = request.withTrigger(secondary);

        assertEquals(List.of(), request.triggers());
        assertEquals(List.of(secondary), updated.triggers());
        assertEquals(Optional.of(secondary), updated.trigger());
        assertFalse(updated.triggeredByPrimary());
    }

    @Test
    void requestEqualityIgnoresTriggers() {
        Trigger primary = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.PRIMARY);
        Trigger secondary = new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-456", TriggerRole.SECONDARY);
        Request withPrimary = new Request("demo", "sample", primary);
        Request withSecondary = new Request("demo", "sample", secondary);
        Request empty = new Request("demo", "sample");

        assertEquals(withPrimary, withSecondary);
        assertEquals(withPrimary, empty);
        assertEquals(withPrimary.hashCode(), withSecondary.hashCode());
    }
}
