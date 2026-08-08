/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationTrigger;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.TriggerRole;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import org.junit.jupiter.api.Test;

import java.util.List;

class ControllerMechanicsTest {
    @Test
    void queueCoalescesKeysAndPreservesTriggerHistory() throws Exception {
        var queue = new ReconciliationQueue();
        var key = new ResourceKey("operators", "sample");
        var first = trigger(ResourceEventType.ADDED);
        var second = trigger(ResourceEventType.UPDATED);

        queue.offer(key, first);
        queue.offer(key, second);
        var work = queue.poll(new ReconciliationQueue.DurationMillis(10)).orElseThrow();

        assertThat(work.key()).isEqualTo(key);
        assertThat(work.triggers()).containsExactly(first, second);
        assertThat(queue.isDrained()).isFalse();
        queue.complete(key);
        assertThat(queue.isDrained()).isTrue();
    }

    private ReconciliationTrigger trigger(ResourceEventType type) {
        var reference = new ResourceReference("v1", "ConfigMap", "operators", "sample", "uid");
        return new ReconciliationTrigger(type, TriggerRole.PRIMARY, reference);
    }

    @Test
    void queueSerializesEventsArrivingWhileKeyIsInFlight() throws Exception {
        var queue = new ReconciliationQueue();
        var key = new ResourceKey("operators", "sample");
        queue.offer(key, trigger(ResourceEventType.ADDED));
        var first = queue.poll(new ReconciliationQueue.DurationMillis(10)).orElseThrow();

        queue.offer(key, trigger(ResourceEventType.UPDATED));
        assertThat(queue.poll(new ReconciliationQueue.DurationMillis(10))).isEmpty();
        queue.complete(first.key());

        var second = queue.poll(new ReconciliationQueue.DurationMillis(10)).orElseThrow();
        assertThat(second.triggers()).extracting(ReconciliationTrigger::eventType)
            .containsExactly(ResourceEventType.UPDATED);
        queue.complete(second.key());
        assertThat(queue.isDrained()).isTrue();
    }

    @Test
    void generationFilterAllowsGenerationDeletionAndFinalizerChanges() {
        var base = resource(1L, null, List.of("cleanup"));
        var unchanged = resource(1L, null, List.of("cleanup"));
        var generation = resource(2L, null, List.of("cleanup"));
        var deleting = resource(1L, "2024-01-01T00:00:00Z", List.of("cleanup"));
        var finalizers = resource(1L, null, List.of());

        assertThat(GenerationFilter.accepts(base, unchanged, true)).isFalse();
        assertThat(GenerationFilter.accepts(base, generation, true)).isTrue();
        assertThat(GenerationFilter.accepts(base, deleting, true)).isTrue();
        assertThat(GenerationFilter.accepts(base, finalizers, true)).isTrue();
        assertThat(GenerationFilter.accepts(base, unchanged, false)).isTrue();
    }

    private ConfigMap resource(Long generation, String deletionTimestamp, List<String> finalizers) {
        return new ConfigMapBuilder().withNewMetadata()
            .withNamespace("operators")
            .withName("sample")
            .withUid("uid")
            .withGeneration(generation)
            .withDeletionTimestamp(deletionTimestamp)
            .withFinalizers(finalizers)
            .endMetadata()
            .build();
    }
}
