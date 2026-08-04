/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The scheduling decision returned by a reconciler.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class ReconcileResult {
    private static final ReconcileResult DONE = new ReconcileResult(null);
    private static final ReconcileResult REQUEUE_NOW = new ReconcileResult(Duration.ZERO);

    private final Duration delay;

    private ReconcileResult(Duration delay) {
        this.delay = delay;
    }

    /**
     * Creates a result that finishes reconciliation without requeueing.
     *
     * @return a result marking the resource as done
     */
    public static ReconcileResult done() {
        return DONE;
    }

    /**
     * Creates a result that requeues the resource immediately.
     *
     * @return a result requesting an immediate requeue
     */
    public static ReconcileResult requeueNow() {
        return REQUEUE_NOW;
    }

    /**
     * Creates a result that requeues the resource after the given delay.
     *
     * @param delay the time to wait before the next reconciliation
     * @return a result requesting a delayed requeue
     * @throws NullPointerException if {@code delay} is null
     * @throws IllegalArgumentException if the delay is zero or negative
     */
    public static ReconcileResult requeueAfter(Duration delay) {
        Objects.requireNonNull(delay, "delay must not be null");
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be positive");
        }
        return new ReconcileResult(delay);
    }

    /**
     * Returns the requested requeue delay, if any.
     *
     * @return the requeue delay, or empty when the resource is done
     */
    public Optional<Duration> requeueDelay() {
        return Optional.ofNullable(delay);
    }

    /**
     * Checks whether reconciliation is complete without a requeue.
     *
     * @return {@code true} when no requeue is requested
     */
    public boolean isDone() {
        return delay == null;
    }
}
