/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.autoconfigure;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorFrameworkPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void exposesRequiredDefaults() {
        var properties = new OperatorFrameworkProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getMode()).isEqualTo(OperatorFrameworkProperties.Mode.COMBINED);
        assertThat(properties.getController().getNamespace()).isNull();
        assertThat(properties.getController().isClusterScoped()).isFalse();
        assertThat(properties.getController().getWorkerThreads()).isEqualTo(1);
        assertThat(properties.getController().getResyncPeriod()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getController().isGenerationChangeFilter()).isTrue();
        assertThat(properties.getController().getStartupRetryDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getLeaderElection().isEnabled()).isFalse();
        assertThat(properties.getLeaderElection().getLeaseName()).isNull();
        assertThat(properties.getLeaderElection().getNamespace()).isNull();
        assertThat(properties.getLeaderElection().getLeaseDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.getLeaderElection().getRenewDeadline()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getLeaderElection().getRetryPeriod()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getRetry().getInitialDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.getRetry().getMaxDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(5);
        assertThat(properties.getRateLimit().getMinimumInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getEvents().isEnabled()).isTrue();
        assertThat(properties.getEvents().getComponent()).isNull();
        assertThat(properties.getEvents().getAggregationWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getEvents().getMaxCacheEntries()).isEqualTo(1000);
    }

    @Test
    void validatesNestedRanges() {
        var properties = new OperatorFrameworkProperties();
        properties.getController().setWorkerThreads(0);
        properties.getLeaderElection().setRenewDeadline(Duration.ofSeconds(16));
        properties.getRetry().setInitialDelay(Duration.ZERO);
        properties.getRateLimit().setMinimumInterval(Duration.ofMillis(-1));
        properties.getEvents().setMaxCacheEntries(0);

        assertThat(violations(properties)).hasSize(5);
    }

    @Test
    void rejectsClusterScopeWithConfiguredNamespace() {
        var properties = new OperatorFrameworkProperties();
        properties.getController().setClusterScoped(true);
        properties.getController().setNamespace("operators");

        assertThat(violations(properties)).singleElement()
                .satisfies(violation -> assertThat(violation.getMessage()).contains("namespace"));
    }

    @Test
    void rejectsInvalidLeaderElectionNames() {
        var properties = new OperatorFrameworkProperties();
        properties.getLeaderElection().setLeaseName("Invalid_Name");

        assertThat(violations(properties)).singleElement()
                .satisfies(violation -> assertThat(violation.getMessage()).contains("DNS labels"));
    }

    @Test
    void acceptsZeroRateLimit() {
        var properties = new OperatorFrameworkProperties();
        properties.getRateLimit().setMinimumInterval(Duration.ZERO);

        assertThat(violations(properties)).isEmpty();
    }

    @Test
    void rejectsUnknownModeDuringBinding() {
        runner.withPropertyValues("operator.framework.mode=not-a-mode")
                .run(context -> assertThat(context).hasFailed());
    }

    private java.util.Set<jakarta.validation.ConstraintViolation<OperatorFrameworkProperties>> violations(
            OperatorFrameworkProperties properties) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OperatorFrameworkProperties.class)
    static class PropertiesConfiguration {
    }
}
