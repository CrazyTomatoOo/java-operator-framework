/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.RuntimeReadiness;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicBoolean;

class RuntimeLifecycleSupportTest {
    @Test
    void leadershipGaugeTracksLeadingFlagAndCloseRemovesIt() {
        var registry = new SimpleMeterRegistry();
        var support = new RuntimeLifecycleSupport(readiness(), new OperatorFrameworkMetrics(registry), null, null);
        var leading = new AtomicBoolean(false);

        support.start(leading::get);
        var gauge = registry.get("operator.framework.leadership").gauge();
        assertThat(gauge.value()).isZero();
        leading.set(true);
        assertThat(gauge.value()).isEqualTo(1.0);

        support.close();
        assertThat(registry.find("operator.framework.leadership").gauge()).isNull();
    }

    @Test
    void closeWithoutStartIsNoop() {
        var support = new RuntimeLifecycleSupport(
                readiness(), new OperatorFrameworkMetrics(new SimpleMeterRegistry()), null, null);
        assertThatCode(support::close).doesNotThrowAnyException();
    }

    private RuntimeReadiness readiness() {
        var readiness = new RuntimeReadiness(event -> { }, false);
        readiness.onApplicationEvent(null);
        return readiness;
    }
}
