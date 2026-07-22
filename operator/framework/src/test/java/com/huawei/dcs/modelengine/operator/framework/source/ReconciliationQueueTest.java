package com.huawei.dcs.modelengine.operator.framework.source;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger;
import com.huawei.dcs.modelengine.operator.framework.reconciler.TriggerRole;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReconciliationQueueTest {
    @Test
    void shouldCoalescePrimaryEventsForSameKeyAndKeepBothTriggers() {
        ReconciliationQueue queue = new ReconciliationQueue();

        queue.add(request("demo", "echo", ResourceEventType.ADD, TriggerRole.PRIMARY));
        queue.add(request("demo", "echo", ResourceEventType.UPDATE, TriggerRole.PRIMARY));

        assertEquals(1, queue.size());
        Request request = queue.poll();
        assertEquals("demo", request.namespace());
        assertEquals("echo", request.name());
        assertTriggers(request, ResourceEventType.ADD, ResourceEventType.UPDATE);
        assertNull(queue.poll());
    }

    @Test
    void shouldCoalesceSecondaryEventWithPrimaryEventForSameKey() {
        ReconciliationQueue queue = new ReconciliationQueue();

        queue.offer(request("demo", "echo", ResourceEventType.ADD, TriggerRole.PRIMARY));
        queue.offer(request("demo", "echo", ResourceEventType.UPDATE, TriggerRole.SECONDARY));

        assertEquals(1, queue.size());
        Request request = queue.poll();
        assertTriggers(request, ResourceEventType.ADD, ResourceEventType.UPDATE);
        assertEquals(List.of(TriggerRole.PRIMARY, TriggerRole.SECONDARY), request.triggers().stream().map(Trigger::role).toList());
    }

    @Test
    void shouldPreserveDeleteTriggerWhenMergedWithUpdateTriggers() {
        ReconciliationQueue queue = new ReconciliationQueue();

        queue.offer(request("demo", "echo", ResourceEventType.UPDATE, TriggerRole.PRIMARY));
        queue.offer(request("demo", "echo", ResourceEventType.DELETE, TriggerRole.SECONDARY));
        queue.offer(request("demo", "echo", ResourceEventType.UPDATE, TriggerRole.PRIMARY));

        assertEquals(1, queue.size());
        Request request = queue.poll();
        assertTriggers(request, ResourceEventType.UPDATE, ResourceEventType.DELETE, ResourceEventType.UPDATE);
        assertEquals(ResourceEventType.DELETE, request.triggers().get(1).eventType());
    }

    @Test
    void shouldNotCoalesceDifferentPrimaryKeys() {
        ReconciliationQueue queue = new ReconciliationQueue();

        queue.offer(request("demo", "first", ResourceEventType.ADD, TriggerRole.PRIMARY));
        queue.offer(request("demo", "second", ResourceEventType.UPDATE, TriggerRole.PRIMARY));

        assertEquals(2, queue.size());
        Request first = queue.poll();
        Request second = queue.poll();
        assertEquals("first", first.name());
        assertEquals("second", second.name());
        assertTriggers(first, ResourceEventType.ADD);
        assertTriggers(second, ResourceEventType.UPDATE);
        assertNull(queue.poll());
    }

    private static Request request(String namespace, String name, ResourceEventType eventType, TriggerRole role) {
        return new Request(namespace, name, trigger(namespace, name, eventType, role));
    }

    private static Trigger trigger(String namespace, String name, ResourceEventType eventType, TriggerRole role) {
        return new Trigger(eventType, "v1", "ConfigMap", namespace, name, eventType.name().toLowerCase(), role);
    }

    private static void assertTriggers(Request request, ResourceEventType... eventTypes) {
        assertEquals(List.of(eventTypes), request.triggers().stream().map(Trigger::eventType).toList());
    }
}
