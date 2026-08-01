/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.RuntimeReadiness;
import com.huawei.dcs.modelengine.operator.framework.internal.policy.ReconcileRateLimitAspect;
import com.huawei.dcs.modelengine.operator.framework.internal.policy.ReconcileRetryAspect;
import java.util.function.BooleanSupplier;

/**
 * Bundles runtime-term readiness, metrics, and policy-state lifecycle actions.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public final class RuntimeLifecycleSupport {
    private final RuntimeReadiness readiness;
    private final OperatorFrameworkMetrics metrics;
    private final ReconcileRetryAspect retry;
    private final ReconcileRateLimitAspect rateLimit;
    private OperatorFrameworkMetrics.GaugeHandle leadershipGauge;

    public RuntimeLifecycleSupport(
            RuntimeReadiness readiness,
            OperatorFrameworkMetrics metrics,
            ReconcileRetryAspect retry,
            ReconcileRateLimitAspect rateLimit) {
        this.readiness = readiness;
        this.metrics = metrics;
        this.retry = retry;
        this.rateLimit = rateLimit;
    }

    RuntimeLifecycleSupport(RuntimeReadiness readiness) {
        this(readiness, new OperatorFrameworkMetrics(null), null, null);
    }

    void start(BooleanSupplier leading) {
        readiness.live();
        leadershipGauge = metrics.leadership("operator-framework", () -> leading.getAsBoolean() ? 1.0 : 0.0);
    }

    void ready() {
        readiness.ready();
    }

    void notReady() {
        readiness.notReady();
    }

    void runtimeStopped() {
        if (retry != null) {
            retry.clear();
        }
        if (rateLimit != null) {
            rateLimit.clear();
        }
    }

    void close() {
        if (leadershipGauge != null) {
            leadershipGauge.close();
        }
    }
}
