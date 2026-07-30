package com.huawei.dcs.modelengine.operator.framework.internal.policy;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Enforces a minimum interval per controller resource by returning delayed work. */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 400)
public final class ReconcileRateLimitAspect {
    // ponytail: load-bearing handoff to ReconcileRetryAspect (order +300, outer). When this aspect
    // (order +400, inner) defers without proceeding, the flag stops retry resetting its failure
    // counter for a scheduling delay. Removal needs an aspect reorder (breaks the documented chain)
    // or a marker in ReconcileResult (leaks rate-limit state into the value object).
    private static final ThreadLocal<Boolean> DEFERRED = ThreadLocal.withInitial(() -> false);

    private final ConcurrentHashMap<ReconcileInvocationKey, Instant> lastInvocations = new ConcurrentHashMap<>();
    private volatile Instant lastSweep;
    private final Duration minimum;
    private final Clock clock;
    private final SpringCallbackIdentifier identifiers;

    public ReconcileRateLimitAspect(
            OperatorFrameworkProperties properties,
            Clock clock,
            ConfigurableListableBeanFactory beanFactory) {
        minimum = properties.getRateLimit().getMinimumInterval();
        this.clock = clock;
        identifiers = new SpringCallbackIdentifier(beanFactory);
    }

    ReconcileRateLimitAspect(OperatorFrameworkProperties properties, Clock clock) {
        this(properties, clock, new DefaultListableBeanFactory());
    }

    @Around("execution(* com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler+.reconcile(..))")
    public Object limit(ProceedingJoinPoint joinPoint) throws Throwable {
        DEFERRED.set(false);
        var controller = identifiers.identify(joinPoint).bean();
        var key = ReconcileInvocationKey.from(joinPoint, controller);
        var now = clock.instant();
        sweepExpired(now);
        var previous = lastInvocations.putIfAbsent(key, now);
        if (previous != null) {
            var remaining = minimum.minus(Duration.between(previous, now));
            if (!remaining.isNegative() && !remaining.isZero()) {
                DEFERRED.set(true);
                return ReconcileResult.requeueAfter(remaining);
            }
            lastInvocations.put(key, now);
        }
        return joinPoint.proceed();
    }

    // ponytail: sweep at most once per minimum window instead of every reconcile (was O(resources)/call)
    private void sweepExpired(Instant now) {
        if (lastSweep == null || Duration.between(lastSweep, now).compareTo(minimum) >= 0) {
            lastInvocations.entrySet().removeIf(entry ->
                    Duration.between(entry.getValue(), now).compareTo(minimum) > 0);
            lastSweep = now;
        }
    }

    /** Clears resource timing state when its controller runtime term ends. */
    public void clear() {
        lastInvocations.clear();
        lastSweep = null;
        DEFERRED.remove();
    }

    int stateSize() {
        return lastInvocations.size();
    }

    static boolean consumeDeferred() {
        var deferred = DEFERRED.get();
        DEFERRED.remove();
        return deferred;
    }
}
