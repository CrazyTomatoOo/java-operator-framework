/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.actuator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class OperatorFrameworkMetricsTest {
    @Test
    void callbackMetricsUseOnlyBoundedTagsAndRecordOnce() {
        var registry = new SimpleMeterRegistry();
        var metrics = new OperatorFrameworkMetrics(registry);

        metrics.callback("validator", "sampleValidator", "success", 1_000);

        var timer = registry.get("operator.framework.callback.duration").timer();
        var counter = registry.get("operator.framework.callback.total").counter();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(counter.count()).isEqualTo(1);
        assertThat(timer.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrder("callback.type", "bean", "outcome");
        assertThat(timer.getId().getTags()).extracting(tag -> tag.getKey())
                .doesNotContainAnyElementsOf(Set.of("resource", "namespace", "uid", "reason", "message", "error"));
    }

    @Test
    void removesRuntimeGaugesAndExportsPrometheusNames() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var metrics = new OperatorFrameworkMetrics(registry);
        var depth = new AtomicInteger(2);
        var gauge = metrics.queueDepth("ConfigMap", depth::doubleValue);
        metrics.retry("ConfigMap");
        metrics.terminalFailure("ConfigMap");
        metrics.informerError("ConfigMap");
        metrics.event("published");
        metrics.event("suppressed");
        metrics.event("failed");

        assertThat(registry.scrape()).contains(
                "operator_framework_queue_depth",
                "operator_framework_reconcile_retries_total",
                "operator_framework_reconcile_terminal_failures_total",
                "operator_framework_informer_errors_total",
                "operator_framework_event_published_total",
                "operator_framework_event_suppressed_total",
                "operator_framework_event_failed_total");
        gauge.close();
        assertThat(registry.find("operator.framework.queue.depth").gauge()).isNull();
    }

    @Test
    void informerErrorCounterIsTaggedByController() {
        var registry = new SimpleMeterRegistry();
        var metrics = new OperatorFrameworkMetrics(registry);
        metrics.informerError("ConfigMap");
        metrics.informerError("ConfigMap");
        var counter = registry.get("operator.framework.informer.errors").counter();
        assertThat(counter.count()).isEqualTo(2);
        assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("controller");
    }
}
