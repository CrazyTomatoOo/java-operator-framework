/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class PolicyMechanicsTest {
    @Test
    void retryUsesExponentialDelaysTerminatesAndResetsAfterSuccess() throws Throwable {
        var properties = new OperatorFrameworkProperties();
        properties.getRetry().setMaxAttempts(3);
        var aspect = new ReconcileRetryAspect(properties);
        var joinPoint = joinPoint();
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("first"))
            .thenThrow(new IllegalStateException("second"))
            .thenThrow(new IllegalStateException("third"));

        assertThat(result(aspect.retry(joinPoint)).requeueDelay()).contains(Duration.ofMillis(500));
        assertThat(result(aspect.retry(joinPoint)).requeueDelay()).contains(Duration.ofSeconds(1));
        assertThatThrownBy(() -> aspect.retry(joinPoint)).isInstanceOf(ReconcileTerminalException.class);

        var resetPoint = joinPoint();
        when(resetPoint.proceed()).thenThrow(new IllegalStateException("first"))
            .thenReturn(ReconcileResult.done())
            .thenThrow(new IllegalStateException("again"));
        assertThat(result(aspect.retry(resetPoint)).requeueDelay()).contains(Duration.ofMillis(500));
        assertThat(result(aspect.retry(resetPoint)).isDone()).isTrue();
        assertThat(result(aspect.retry(resetPoint)).requeueDelay()).contains(Duration.ofMillis(500));
    }

    private ProceedingJoinPoint joinPoint() {
        return joinPoint("sample");
    }

    private ProceedingJoinPoint joinPoint(String name) {
        var joinPoint = mock(ProceedingJoinPoint.class);
        var context = ReconciliationContext.<ConfigMap>withoutCache(new ResourceKey("operators", name), List.of());
        when(joinPoint.getArgs()).thenReturn(new Object[]{resource(name), context});
        when(joinPoint.getTarget()).thenReturn(new TestController());
        return joinPoint;
    }

    private ConfigMap resource(String name) {
        return new ConfigMapBuilder().withNewMetadata()
            .withNamespace("operators")
            .withName(name)
            .withUid("uid-" + name)
            .withGeneration(1L)
            .endMetadata()
            .build();
    }

    private ReconcileResult result(Object value) {
        return (ReconcileResult) value;
    }

    @Test
    void rateLimitReturnsRemainingDelayWithoutSleepingOrInvokingTarget() throws Throwable {
        var properties = new OperatorFrameworkProperties();
        var clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        var aspect = new ReconcileRateLimitAspect(properties, clock);
        var joinPoint = joinPoint();
        when(joinPoint.proceed()).thenReturn(ReconcileResult.done());

        assertThat(result(aspect.limit(joinPoint)).isDone()).isTrue();
        assertThat(result(aspect.limit(joinPoint)).requeueDelay()).contains(Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(5));
        assertThat(result(aspect.limit(joinPoint)).isDone()).isTrue();
        verify(joinPoint, times(2)).proceed();
    }

    @Test
    void rateLimitExpiresInactiveResourceKeysOpportunistically() throws Throwable {
        var clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        var aspect = new ReconcileRateLimitAspect(new OperatorFrameworkProperties(), clock);
        var first = joinPoint("first");
        var second = joinPoint("second");
        when(first.proceed()).thenReturn(ReconcileResult.done());
        when(second.proceed()).thenReturn(ReconcileResult.done());

        aspect.limit(first);
        assertThat(aspect.stateSize()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(6));
        aspect.limit(second);

        assertThat(aspect.stateSize()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void orderedRetryAndRateAspectsPreserveAttemptStateAcrossRateDeferral() throws Exception {
        var properties = new OperatorFrameworkProperties();
        properties.getRetry().setMaxAttempts(2);
        var clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        var target = new FailingReconciler();
        var factory = new AspectJProxyFactory(target);
        factory.setInterfaces(Reconciler.class);
        factory.addAspect(new ReconcileRetryAspect(properties));
        factory.addAspect(new ReconcileRateLimitAspect(properties, clock));
        var proxy = (Reconciler<ConfigMap>) factory.getProxy();
        var context = ReconciliationContext.<ConfigMap>withoutCache(new ResourceKey("operators", "sample"), List.of());

        assertThat(proxy.reconcile(resource(), context).requeueDelay()).contains(Duration.ofMillis(500));
        assertThat(proxy.reconcile(resource(), context).requeueDelay()).contains(Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> proxy.reconcile(resource(), context)).isInstanceOf(ReconcileTerminalException.class);
        assertThat(target.calls).hasValue(2);
    }

    private ConfigMap resource() {
        return resource("sample");
    }

    @Test
    void aspectsHaveRequiredOrder() {
        assertThat(order(ReconcileObservationAspect.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
        assertThat(order(ReconcileExceptionAspect.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 200);
        assertThat(order(ReconcileRetryAspect.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);
        assertThat(order(ReconcileRateLimitAspect.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 400);
    }

    private int order(Class<?> type) {
        return AnnotationUtils.findAnnotation(type, Order.class).value();
    }

    private static final class TestController {}

    private static final class FailingReconciler implements Reconciler<ConfigMap> {
        private final AtomicInteger calls = new AtomicInteger();

        /**
         * Counts the call, then fails with a temporary error.
         *
         * @param resource the resource to reconcile
         * @param context the reconciliation context
         * @return never returns
         */
        @Override
        public ReconcileResult reconcile(ConfigMap resource, ReconciliationContext<ConfigMap> context) {
            calls.incrementAndGet();
            throw new IllegalStateException("temporary failure");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * Returns the clock's zone.
         *
         * @return always UTC
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * Ignores the requested zone; the clock stays on UTC.
         *
         * @param zone the requested zone
         * @return this clock
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * Returns the current test-controlled instant.
         *
         * @return the current instant
         */
        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
