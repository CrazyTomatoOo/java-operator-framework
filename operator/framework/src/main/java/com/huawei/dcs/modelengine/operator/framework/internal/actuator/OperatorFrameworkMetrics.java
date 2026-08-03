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

    /**
     * Creates the facade; a {@code null} registry disables all recording (no-op mode).
     *
     * @param registry the meter registry to record into, or {@code null} to disable metrics
     */
    public OperatorFrameworkMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records a webhook callback invocation: duration timer plus a total counter.
     *
     * @param type the callback type (validator, mutator, or converter)
     * @param bean the callback bean name
     * @param outcome the invocation outcome
     * @param nanoseconds the invocation duration in nanoseconds
     */
    public void callback(String type, String bean, String outcome, long nanoseconds) {
        if (registry == null) {
            return;
        }
        var tags = Tags.of("callback.type", type, "bean", bean, "outcome", outcome);
        Timer.builder("operator.framework.callback.duration").tags(tags).register(registry)
                .record(nanoseconds, TimeUnit.NANOSECONDS);
        registry.counter("operator.framework.callback.total", tags).increment();
    }

    /**
     * Counts a reconciliation retry for a controller.
     *
     * @param controller the controller name
     */
    public void retry(String controller) {
        increment("operator.framework.reconcile.retries", "controller", controller);
    }

    /**
     * Counts a terminal reconciliation failure (retries exhausted) for a controller.
     *
     * @param controller the controller name
     */
    public void terminalFailure(String controller) {
        increment("operator.framework.reconcile.terminal.failures", "controller", controller);
    }

    /**
     * Counts an informer error for a controller.
     *
     * @param controller the controller name
     */
    public void informerError(String controller) {
        increment("operator.framework.informer.errors", "controller", controller);
    }

    /**
     * Counts an event publication outcome; unknown outcomes are ignored.
     *
     * @param outcome the outcome: {@code published}, {@code suppressed}, or {@code failed}
     */
    public void event(String outcome) {
        if ("published".equals(outcome) || "suppressed".equals(outcome) || "failed".equals(outcome)) {
            increment("operator.framework.event." + outcome, "outcome", outcome);
        }
    }

    /**
     * Registers a gauge reporting the current work-queue depth of a controller.
     *
     * @param controller the controller name
     * @param value supplier of the current queue depth
     * @return handle used to remove the gauge when the owning runtime stops
     */
    public GaugeHandle queueDepth(String controller, DoubleSupplier value) {
        return gauge("operator.framework.queue.depth", controller, value);
    }

    /**
     * Registers a gauge reporting whether the informer cache of a controller has synced.
     *
     * @param controller the controller name
     * @param value supplier of the synced flag (1.0 when synced, 0.0 otherwise)
     * @return handle used to remove the gauge when the owning runtime stops
     */
    public GaugeHandle informerSynced(String controller, DoubleSupplier value) {
        return gauge("operator.framework.informer.synced", controller, value);
    }

    /**
     * Registers a gauge reporting whether a controller currently holds leadership.
     *
     * @param controller the controller name
     * @param value supplier of the leadership flag (1.0 when leading, 0.0 otherwise)
     * @return handle used to remove the gauge when the owning runtime stops
     */
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

        /** Removes the gauge from the registry; safe to call more than once. */
        @Override
        public void close() {
            if (registry != null) {
                registry.remove(meter);
            }
        }
    }
}
