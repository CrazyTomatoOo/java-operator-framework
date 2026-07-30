package com.huawei.dcs.modelengine.operator.framework.internal.actuator;

import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.OperatorFrameworkLifecycle;
import com.huawei.dcs.modelengine.operator.framework.internal.webhook.WebhookCallbackRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.Map;

/** Single Actuator health contribution for all enabled operator framework modes. */
@RequiredArgsConstructor
public final class OperatorFrameworkHealthIndicator implements HealthIndicator {
    private final OperatorFrameworkProperties properties;
    private final ObjectProvider<OperatorFrameworkLifecycle> lifecycle;
    private final ObjectProvider<WebhookCallbackRegistry> callbacks;
    private final RuntimeReadiness readiness;


    @Override
    public Health health() {
        var builder = status();
        builder.withDetail("mode", properties.getMode().name().toLowerCase(java.util.Locale.ROOT));
        builder.withDetail("liveness", readiness.isLive());
        builder.withDetail("readiness", readiness.isReady());
        if (properties.getMode() != OperatorFrameworkProperties.Mode.WEBHOOK) {
            builder.withDetail("controller", controllerDetails());
        }
        if (properties.getMode() != OperatorFrameworkProperties.Mode.CONTROLLER) {
            builder.withDetail("webhook", webhookDetails());
        }
        return builder.build();
    }

    private Health.Builder status() {
        if (!readiness.isLive()) {
            return Health.down();
        }
        if (!readiness.isReady()) {
            return Health.status(Status.OUT_OF_SERVICE);
        }
        return Health.up();
    }

    private Map<String, Object> controllerDetails() {
        var current = lifecycle.getIfAvailable();
        if (current == null) {
            return Map.of(
                    "state", "stopped",
                    "workersRunning", false,
                    "informerSynced", false,
                    "leadership", false,
                    "queueDepth", 0,
                    "lastFailure", "none");
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("state", current.isRunning() ? "running" : "stopped");
        details.put("workersRunning", current.isWorkerRunning());
        details.put("informerSynced", current.isInformerSynced());
        details.put("leadership", current.isLeading());
        details.put("queueDepth", current.queueDepth());
        details.put("lastFailure", current.lastFailure());
        return details;
    }

    private Map<String, Object> webhookDetails() {
        var current = callbacks.getIfAvailable();
        var details = new LinkedHashMap<String, Object>();
        details.put("validators", current == null ? 0 : current.validatorCount());
        details.put("mutators", current == null ? 0 : current.mutatorCount());
        details.put("converters", current == null ? 0 : current.converterCount());
        details.put("lastFailure", current == null ? "none" : current.lastFailure().orElse("none"));
        return details;
    }
}
