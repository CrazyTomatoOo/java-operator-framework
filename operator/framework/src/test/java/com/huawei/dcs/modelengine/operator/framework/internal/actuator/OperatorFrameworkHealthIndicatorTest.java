package com.huawei.dcs.modelengine.operator.framework.internal.actuator;

import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.OperatorFrameworkLifecycle;
import com.huawei.dcs.modelengine.operator.framework.internal.webhook.WebhookCallbackRegistry;
import io.fabric8.kubernetes.api.model.ConfigMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorFrameworkHealthIndicatorTest {
    @Test
    void publishesReadinessAndLivenessTransitions() {
        var events = new ArrayList<Object>();
        var readiness = new RuntimeReadiness(events::add);

        readiness.ready();
        readiness.notReady();
        readiness.broken();
        readiness.live();

        var states = events.stream()
                .map(event -> (Object) ((AvailabilityChangeEvent<?>) event).getState()).toList();
        assertThat(states).containsExactly(ReadinessState.ACCEPTING_TRAFFIC, ReadinessState.REFUSING_TRAFFIC,
                LivenessState.BROKEN, LivenessState.CORRECT);
    }

    @Test
    void reportsWebhookCountsAndSafeFailure() {
        try (var context = callbackContext()) {
            var callbacks = new WebhookCallbackRegistry(context.getBeanFactory());
            callbacks.recordFailure("validator", "validator");
            var properties = new OperatorFrameworkProperties();
            properties.setMode(OperatorFrameworkProperties.Mode.WEBHOOK);
            var readiness = new RuntimeReadiness();
            readiness.ready();
            var health = new OperatorFrameworkHealthIndicator(
                    properties, emptyProvider(), provider(callbacks), readiness).health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            var details = (Map<?, ?>) health.getDetails().get("webhook");
            assertThat(details.get("validators")).isEqualTo(1);
            assertThat(details.get("lastFailure"))
                    .isEqualTo("validator callback 'validator' failed");
        }
    }

    @Test
    void reportsControllerStateAndLiveness() {
        var lifecycle = mock(OperatorFrameworkLifecycle.class);
        when(lifecycle.isRunning()).thenReturn(true);
        when(lifecycle.isInformerSynced()).thenReturn(true);
        when(lifecycle.isLeading()).thenReturn(true);
        var properties = new OperatorFrameworkProperties();
        properties.setMode(OperatorFrameworkProperties.Mode.CONTROLLER);
        var readiness = new RuntimeReadiness();
        readiness.ready();
        var indicator = new OperatorFrameworkHealthIndicator(
                properties, provider(lifecycle), emptyProvider(), readiness);

        var details = (Map<?, ?>) indicator.health().getDetails().get("controller");
        assertThat(details.get("state")).isEqualTo("running");
        assertThat(details.get("informerSynced")).isEqualTo(true);
        assertThat(details.get("leadership")).isEqualTo(true);
        readiness.broken();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private GenericApplicationContext callbackContext() {
        var context = new GenericApplicationContext();
        context.registerBean("validator", TypedValidator.class, TypedValidator::new);
        context.refresh();
        return context;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        var provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) mock(ObjectProvider.class);
    }

    static final class TypedValidator implements AdmissionValidator<ConfigMap> {
        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext context) {
            return AdmissionDecision.allow();
        }
    }
}
