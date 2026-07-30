package com.huawei.dcs.modelengine.operator.framework.internal.policy;

import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Logs callback failures after execution-policy advice and counts terminal reconciler failures. */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 200)
public final class ReconcileExceptionAspect {
    private final OperatorFrameworkMetrics metrics;
    private final SpringCallbackIdentifier identifiers;

    public ReconcileExceptionAspect(
            OperatorFrameworkMetrics metrics,
            ConfigurableListableBeanFactory beanFactory) {
        this.metrics = metrics;
        identifiers = new SpringCallbackIdentifier(beanFactory);
    }

    @Around(ReconcileObservationAspect.CALLBACKS)
    public Object report(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Throwable exception) {
            var identity = identifiers.identify(joinPoint);
            if (exception instanceof ReconcileTerminalException) {
                metrics.terminalFailure(identity.bean());
            }
            var logger = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
            logger.error("{} callback '{}' failed with {}",
                    identity.type(), identity.bean(), exception.getClass().getName());
            logger.debug("Callback failure stack trace", exception);
            throw exception;
        }
    }
}
