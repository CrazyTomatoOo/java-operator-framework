package com.huawei.dcs.modelengine.operator.framework.reconciler;

import java.time.Duration;

/** Outcome requested by a reconciler after handling a resource. */
public final class Result {
    private final boolean requeue;
    private final Duration requeueAfter;
    private final Throwable error;

    public Result(boolean requeue, Duration requeueAfter, Throwable error) {
        this.requeue = requeue;
        this.requeueAfter = requeueAfter;
        this.error = error;
    }

    public static Result done() {
        return new Result(false, null, null);
    }

    public static Result requeueNow() {
        return new Result(true, null, null);
    }

    public static Result requeueAfter(Duration duration) {
        return new Result(true, duration, null);
    }

    public static Result error(Throwable error) {
        return new Result(true, null, error);
    }

    public boolean requeue() {
        return this.requeue;
    }

    public Duration requeueAfter() {
        return this.requeueAfter;
    }

    public Throwable error() {
        return this.error;
    }
}
