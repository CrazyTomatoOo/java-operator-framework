package com.huawei.dcs.modelengine.operator.framework.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ResourceEventTypeTest {
    @Test
    void shouldExposeAllResourceEventTypes() {
        assertArrayEquals(
                new ResourceEventType[] {
                        ResourceEventType.ADD,
                        ResourceEventType.UPDATE,
                        ResourceEventType.DELETE,
                        ResourceEventType.RESYNC
                },
                ResourceEventType.values()
        );
    }
}
