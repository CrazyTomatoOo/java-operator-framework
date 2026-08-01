/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.actuator;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * Small bounded-tag metrics facade shared by controller, webhook, and event runtime code.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public final class OperatorFrameworkMetrics {
    private final MeterRegistry registry;

    public OperatorFrameworkMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void callback(String type, String bean, String outcome, long nanoseconds) {
        if (registry == null) {
            return;
        }
        var tags = Tags.of("callback.type", type, "bean", bean, "outcome", outcome);
        Timer.builder("operator.framework.callback.duration").tags(tags).register(registry)
                .record(nanoseconds, TimeUnit.NANOSECONDS);
        registry.counter("operator.framework.callback.total", tags).increment();
    }

    public void retry(String controller) {
        increment("operator.framework.reconcile.retries", "controller", controller);
    }

    public void terminalFailure(String controller) {
        increment("operator.framework.reconcile.terminal.failures", "controller", controller);
    }

    public void informerError(String controller) {
        increment("operator.framework.informer.errors", "controller", controller);
    }

    public void event(String outcome) {
        if ("published".equals(outcome) || "suppressed".equals(outcome) || "failed".equals(outcome)) {
            increment("operator.framework.event." + outcome, "outcome", outcome);
        }
    }

    public GaugeHandle queueDepth(String controller, DoubleSupplier value) {
        return gauge("operator.framework.queue.depth", controller, value);
    }

    public GaugeHandle informerSynced(String controller, DoubleSupplier value) {
        return gauge("operator.framework.informer.synced", controller, value);
    }

    public GaugeHandle leadership(String controller, DoubleSupplier value) {
        return gauge("operator.framework.leadership", controller, value);
    }

    private void increment(String name, String tag, String value) {
        if (registry != null) {
            registry.counter(name, tag, value).increment();
        }
    }

    private GaugeHandle gauge(String name, String controller, DoubleSupplier value) {
        if (registry == null) {
            return GaugeHandle.NOOP;
        }
        var meter = Gauge.builder(name, value, DoubleSupplier::getAsDouble)
                .tag("controller", controller)
                .register(registry);
        return new GaugeHandle(registry, meter);
    }

    /** Removes a runtime gauge when its owning runtime is stopped. */
    public static final class GaugeHandle implements AutoCloseable {
        private static final GaugeHandle NOOP = new GaugeHandle(null, null);

        private final MeterRegistry registry;
        private final Meter meter;

        private GaugeHandle(MeterRegistry registry, Meter meter) {
            this.registry = registry;
            this.meter = meter;
        }

        @Override
        public void close() {
            if (registry != null) {
                registry.remove(meter);
            }
        }
    }
}
