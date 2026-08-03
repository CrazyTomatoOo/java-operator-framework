/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.policy;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

/**
 * Converts transient reconciler exceptions into delayed results without blocking worker threads.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 300)
public final class ReconcileRetryAspect {
    private final ConcurrentHashMap<ReconcileInvocationKey, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final OperatorFrameworkProperties.Retry properties;
    private final OperatorFrameworkMetrics metrics;
    private final SpringCallbackIdentifier identifiers;
    private final DoubleSupplier jitterFactor;

    /**
     * Creates the aspect with production jitter applied to retry delays.
     *
     * @param properties the framework properties carrying the retry configuration
     * @param metrics the metrics recorder for retry counts
     * @param beanFactory the bean factory used to resolve controller bean names
     */
    public ReconcileRetryAspect(
            OperatorFrameworkProperties properties,
            OperatorFrameworkMetrics metrics,
            ConfigurableListableBeanFactory beanFactory) {
        this(properties, metrics, beanFactory, () -> ThreadLocalRandom.current().nextDouble(0.8, 1.2));
    }

    ReconcileRetryAspect(OperatorFrameworkProperties properties) {
        this(properties, new OperatorFrameworkMetrics(null), new DefaultListableBeanFactory(), () -> 1.0);
    }

    // ponytail: jitter factor seam lets tests pin deterministic delays while production spreads retries
    /**
     * Creates the aspect with an explicit jitter factor, allowing tests to pin deterministic delays.
     *
     * @param properties the framework properties carrying the retry configuration
     * @param metrics the metrics recorder for retry counts
     * @param beanFactory the bean factory used to resolve controller bean names
     * @param jitterFactor supplies the multiplier applied to each computed retry delay
     */
    public ReconcileRetryAspect(OperatorFrameworkProperties properties, OperatorFrameworkMetrics metrics,
            ConfigurableListableBeanFactory beanFactory, DoubleSupplier jitterFactor) {
        this.properties = properties.getRetry();
        this.metrics = metrics;
        this.identifiers = new SpringCallbackIdentifier(beanFactory);
        this.jitterFactor = jitterFactor;
    }

    /**
     * Converts a transient reconciler exception into a delayed requeue with exponential backoff and
     * jitter, and escalates to a terminal exception once the configured attempts are exhausted.
     *
     * @param joinPoint the intercepted reconcile invocation
     * @return the reconciler result, or a requeue-after result for a retriable failure
     * @throws ReconcileTerminalException when the maximum configured attempts are exhausted
     * @throws Throwable any error other than a reconciler exception, propagated unchanged
     */
    @Around("execution(* com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler+.reconcile(..))")
    public Object retry(ProceedingJoinPoint joinPoint) throws Throwable {
        var controller = identifiers.identify(joinPoint).bean();
        var key = ReconcileInvocationKey.from(joinPoint, controller);
        ReconcileRateLimitAspect.consumeDeferred();
        try {
            var result = joinPoint.proceed();
            if (!ReconcileRateLimitAspect.consumeDeferred()) {
                attempts.remove(key);
            }
            return result;
        } catch (ReconcileTerminalException exception) {
            throw exception;
        } catch (Exception exception) {
            return retryResult(key, exception, controller);
        }
    }

    /** Clears retry attempts when their controller runtime term ends. */
    public void clear() {
        attempts.clear();
    }

    int stateSize() {
        return attempts.size();
    }

    private ReconcileResult retryResult(
            ReconcileInvocationKey key,
            Exception exception,
            String controller) {
        var attempt = attempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        if (attempt >= properties.getMaxAttempts()) {
            attempts.remove(key);
            throw new ReconcileTerminalException("reconciliation failed after " + attempt + " attempts", exception);
        }
        metrics.retry(controller);
        return ReconcileResult.requeueAfter(delay(attempt));
    }

    private Duration delay(int attempt) {
        try {
            var multiplier = attempt >= Long.SIZE ? Long.MAX_VALUE : 1L << (attempt - 1);
            var base = properties.getInitialDelay().multipliedBy(multiplier);
            var capped = base.compareTo(properties.getMaxDelay()) > 0 ? properties.getMaxDelay() : base;
            // ponytail: ±20% jitter prevents thundering-herd retries when many resources fail together
            return Duration.ofMillis((long) (capped.toMillis() * jitterFactor.getAsDouble()));
        } catch (ArithmeticException exception) {
            return properties.getMaxDelay();
        }
    }
}
