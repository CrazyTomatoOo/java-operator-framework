package com.huawei.dcs.modelengine.operator.framework.source;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceEventTest {
    @Test
    void addEventHasNoOldResource() {
        ConfigMap resource = configMap("demo", "created");

        ResourceEvent<ConfigMap> event = new ResourceEvent<>(ResourceEventType.ADD, resource, null);

        assertEquals(ResourceEventType.ADD, event.type());
        assertEquals(resource, event.resource());
        assertNull(event.oldResource());
    }

    @Test
    void updateEventHasOldResource() {
        ConfigMap oldResource = configMap("demo", "old");
        ConfigMap resource = configMap("demo", "updated");

        ResourceEvent<ConfigMap> event = new ResourceEvent<>(ResourceEventType.UPDATE, resource, oldResource);

        assertEquals(ResourceEventType.UPDATE, event.type());
        assertEquals(resource, event.resource());
        assertNotNull(event.oldResource());
        assertEquals(oldResource, event.oldResource());
    }

    @Test
    void deleteEventHasNoOldResource() {
        ConfigMap resource = configMap("demo", "deleted");

        ResourceEvent<ConfigMap> event = new ResourceEvent<>(ResourceEventType.DELETE, resource, null);

        assertEquals(ResourceEventType.DELETE, event.type());
        assertEquals(resource, event.resource());
        assertNull(event.oldResource());
    }

    private static ConfigMap configMap(String namespace, String name) {
        ConfigMap configMap = new ConfigMap();
        configMap.setMetadata(new ObjectMetaBuilder().withNamespace(namespace).withName(name).build());
        return configMap;
    }
}
