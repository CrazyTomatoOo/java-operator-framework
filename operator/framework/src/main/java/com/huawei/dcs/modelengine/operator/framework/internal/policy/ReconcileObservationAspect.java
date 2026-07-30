package com.huawei.dcs.modelengine.operator.framework.internal.policy;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.MutationResult;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Records exactly one observation and metric timing around every user callback invocation. */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public final class ReconcileObservationAspect {
    static final String CALLBACKS =
            "execution(* com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler+.reconcile(..))"
            + " || execution(* com.huawei.dcs.modelengine.operator.framework.api.webhook."
            + "AdmissionValidator+.validate(..))"
            + " || execution(* com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionMutator+.mutate(..))"
            + " || execution(* com.huawei.dcs.modelengine.operator.framework.api.webhook."
            + "ResourceConverter+.convert(..))";

    private final ObservationRegistry observations;
    private final OperatorFrameworkMetrics metrics;
    private final SpringCallbackIdentifier identifiers;

    public ReconcileObservationAspect(
            ObservationRegistry observations,
            OperatorFrameworkMetrics metrics,
            ConfigurableListableBeanFactory beanFactory) {
        this.observations = observations;
        this.metrics = metrics;
        identifiers = new SpringCallbackIdentifier(beanFactory);
    }

    @Around(CALLBACKS)
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        var identity = identifiers.identify(joinPoint);
        var observation = observation(identity).start();
        var started = System.nanoTime();
        var outcome = "success";
        try {
            var result = joinPoint.proceed();
            outcome = outcome(result);
            return result;
        } catch (Throwable exception) {
            outcome = "error";
            observation.error(exception);
            throw exception;
        } finally {
            metrics.callback(identity.type(), identity.bean(), outcome, System.nanoTime() - started);
            observation.stop();
        }
    }

    private String outcome(Object result) {
        if (result instanceof ReconcileResult reconcile) {
            return reconcileOutcome(reconcile);
        }
        if (result instanceof AdmissionDecision admission) {
            return admissionOutcome(admission);
        }
        if (result instanceof MutationResult<?> mutation) {
            return mutationOutcome(mutation);
        }
        if (result instanceof ConversionResult<?> conversion) {
            return conversionOutcome(conversion);
        }
        return "failure";
    }

    private String reconcileOutcome(ReconcileResult result) {
        return result.isDone() ? "success" : "requeue";
    }

    private String admissionOutcome(AdmissionDecision result) {
        return result.isAllowed() ? "success" : "denied";
    }

    private String mutationOutcome(MutationResult<?> result) {
        return result.status() == MutationResult.Status.DENIED ? "denied" : "success";
    }

    private String conversionOutcome(ConversionResult<?> result) {
        return result.isConverted() ? "success" : "failure";
    }

    private Observation observation(SpringCallbackIdentifier.Identity identity) {
        return Observation.createNotStarted("operator.framework.callback", observations)
                .lowCardinalityKeyValue("callback.type", identity.type())
                .lowCardinalityKeyValue("bean", identity.bean());
    }
}
