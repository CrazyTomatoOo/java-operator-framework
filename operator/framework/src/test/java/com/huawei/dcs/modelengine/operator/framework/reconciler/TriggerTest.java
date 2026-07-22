package com.huawei.dcs.modelengine.operator.framework.reconciler;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TriggerTest {

    @Test
    void shouldCaptureTriggerDetailsFromResourceMetadata() {
        ConfigMap resource = new ConfigMap();
        resource.setApiVersion("v1");
        resource.setKind("ConfigMap");
        resource.setMetadata(new ObjectMeta());
        resource.getMetadata().setNamespace("demo");
        resource.getMetadata().setName("sample");
        resource.getMetadata().setUid("uid-123");

        Trigger trigger = Trigger.from(resource, ResourceEventType.ADD, TriggerRole.PRIMARY);

        assertEquals(ResourceEventType.ADD, trigger.eventType());
        assertEquals("v1", trigger.apiVersion());
        assertEquals("ConfigMap", trigger.kind());
        assertEquals("demo", trigger.namespace());
        assertEquals("sample", trigger.name());
        assertEquals("uid-123", trigger.uid());
        assertEquals(TriggerRole.PRIMARY, trigger.role());
    }

    @Test
    void shouldRejectNullMetadata() {
        ConfigMap resource = new ConfigMap();

        assertThrows(NullPointerException.class,
                () -> Trigger.from(resource, ResourceEventType.ADD, TriggerRole.SECONDARY));
    }
    @Test
    void shouldRejectNullEventType() {
        ConfigMap resource = new ConfigMap();
        resource.setMetadata(new ObjectMeta());

        assertThrows(NullPointerException.class,
                () -> new Trigger(null, "v1", "ConfigMap", "demo", "sample", "uid-123", TriggerRole.PRIMARY));
    }

    @Test
    void shouldRejectNullRole() {
        assertThrows(NullPointerException.class,
                () -> new Trigger(ResourceEventType.ADD, "v1", "ConfigMap", "demo", "sample", "uid-123", null));
    }

    @Test
    void shouldRejectNullResource() {
        assertThrows(NullPointerException.class,
                () -> Trigger.from(null, ResourceEventType.ADD, TriggerRole.SECONDARY));
    }

}
