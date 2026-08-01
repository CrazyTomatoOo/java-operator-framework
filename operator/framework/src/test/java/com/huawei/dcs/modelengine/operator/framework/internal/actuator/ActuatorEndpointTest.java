/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.actuator;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ActuatorEndpointTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "operator.framework.mode=webhook",
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.endpoint.health.show-details=always",
                "management.endpoint.health.probes.enabled=true",
                "management.prometheus.metrics.export.enabled=true"
        })
class ActuatorEndpointTest {
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private AdmissionValidator<ConfigMap> validator;
    @Autowired
    private RuntimeReadiness frameworkReadiness;

    @Test
    void exposesStandardHealthProbesAndPrometheusEndpoint() throws Exception {
        validator.validate(resource(), admissionContext());
        assertThat(frameworkReadiness.isReady()).isTrue();

        assertThat(rest.getForEntity("/actuator/health", String.class).getBody())
                .contains("operatorFramework", "webhook", "UP");
        var liveness = rest.getForEntity("/actuator/health/liveness", String.class);
        assertThat(liveness.getStatusCode().is2xxSuccessful()).as(liveness.getBody()).isTrue();
        var readiness = rest.getForEntity("/actuator/health/readiness", String.class);
        assertThat(readiness.getStatusCode().is2xxSuccessful()).as(readiness.getBody()).isTrue();
        var prometheus = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(prometheus.getBody()).contains(
                "operator_framework_callback_duration_seconds_count",
                "operator_framework_callback_total");
    }

    private ConfigMap resource() {
        return new ConfigMapBuilder().withApiVersion("v1").withKind("ConfigMap")
                .withNewMetadata().withNamespace("default").withName("sample").endMetadata().build();
    }

    private AdmissionContext admissionContext() {
        return new AdmissionContext("request-1", "CREATE",
                new ResourceReference("v1", "ConfigMap", "default", "sample", null), false,
                new AdmissionContext.UserIdentity("alice", null, List.of(), Map.of()));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {
        @Bean
        TypedValidator validator() {
            return new TypedValidator();
        }
    }

    static class TypedValidator implements AdmissionValidator<ConfigMap> {
        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext context) {
            return AdmissionDecision.allow();
        }
    }
}
