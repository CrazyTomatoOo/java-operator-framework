package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** The scheduling decision returned by a reconciler. */
public final class ReconcileResult {
    private static final ReconcileResult DONE = new ReconcileResult(null);
    private static final ReconcileResult REQUEUE_NOW = new ReconcileResult(Duration.ZERO);

    private final Duration delay;

    private ReconcileResult(Duration delay) {
        this.delay = delay;
    }

    public static ReconcileResult done() {
        return DONE;
    }

    public static ReconcileResult requeueNow() {
        return REQUEUE_NOW;
    }

    public static ReconcileResult requeueAfter(Duration delay) {
        Objects.requireNonNull(delay, "delay must not be null");
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be positive");
        }
        return new ReconcileResult(delay);
    }

    public Optional<Duration> requeueDelay() {
        return Optional.ofNullable(delay);
    }

    public boolean isDone() {
        return delay == null;
    }
}
