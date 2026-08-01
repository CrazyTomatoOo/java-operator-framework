/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkHealthIndicator;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.RuntimeReadiness;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.Fabric8ControllerRuntimeFactory;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.FrameworkKubernetesClientOwnership;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.OperatorFrameworkLifecycle;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.RuntimeLifecycleSupport;
import com.huawei.dcs.modelengine.operator.framework.internal.discovery.ControllerRegistrationDiscovery;
import com.huawei.dcs.modelengine.operator.framework.internal.event.AggregatingKubernetesEventPublisher;
import com.huawei.dcs.modelengine.operator.framework.internal.leader.Fabric8LeaderElectionAdapter;
import com.huawei.dcs.modelengine.operator.framework.internal.policy.ReconcileExceptionAspect;
import com.huawei.dcs.modelengine.operator.framework.internal.policy.ReconcileObservationAspect;
import com.huawei.dcs.modelengine.operator.framework.internal.policy.ReconcileRateLimitAspect;
import com.huawei.dcs.modelengine.operator.framework.internal.policy.ReconcileRetryAspect;
import com.huawei.dcs.modelengine.operator.framework.internal.webhook.AdmissionWebhookController;
import com.huawei.dcs.modelengine.operator.framework.internal.webhook.ConversionWebhookController;
import com.huawei.dcs.modelengine.operator.framework.internal.webhook.WebhookCallbackRegistry;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.time.Clock;
import java.time.Duration;

/**
 * Spring Boot auto-configuration for the operator framework.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
@AutoConfiguration
@EnableConfigurationProperties(OperatorFrameworkProperties.class)
@ConditionalOnProperty(prefix = "operator.framework", name = "enabled", matchIfMissing = true)
public class OperatorFrameworkAutoConfiguration {
    @Bean
    OperatorFrameworkMetrics operatorFrameworkMetrics(ObjectProvider<MeterRegistry> registry) {
        return new OperatorFrameworkMetrics(registry.getIfAvailable());
    }

    @Bean
    RuntimeReadiness operatorRuntimeReadiness(
            ApplicationEventPublisher publisher,
            OperatorFrameworkProperties properties) {
        var webhookOnly = properties.getMode() == OperatorFrameworkProperties.Mode.WEBHOOK;
        return new RuntimeReadiness(publisher, webhookOnly);
    }

    @Bean
    ReconcileObservationAspect reconcileObservationAspect(
            ObjectProvider<ObservationRegistry> registry,
            OperatorFrameworkMetrics metrics,
            ConfigurableListableBeanFactory beanFactory) {
        return new ReconcileObservationAspect(
                registry.getIfAvailable(() -> ObservationRegistry.NOOP), metrics, beanFactory);
    }

    @Bean
    ReconcileExceptionAspect reconcileExceptionAspect(
            OperatorFrameworkMetrics metrics,
            ConfigurableListableBeanFactory beanFactory) {
        return new ReconcileExceptionAspect(metrics, beanFactory);
    }

    @Bean("operatorFrameworkHealthIndicator")
    HealthIndicator operatorFrameworkHealthIndicator(
            OperatorFrameworkProperties properties,
            ObjectProvider<OperatorFrameworkLifecycle> lifecycle,
            ObjectProvider<WebhookCallbackRegistry> callbacks,
            RuntimeReadiness readiness) {
        return new OperatorFrameworkHealthIndicator(properties, lifecycle, callbacks, readiness);
    }

    /** Controller-side beans are absent in webhook-only mode. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("'${operator.framework.mode:combined}'.equalsIgnoreCase('controller')"
            + " || '${operator.framework.mode:combined}'.equalsIgnoreCase('combined')")
    static class ControllerSideConfiguration {
        @Bean
        FrameworkKubernetesClientOwnership frameworkKubernetesClientOwnership() {
            return new FrameworkKubernetesClientOwnership();
        }

        @Bean
        @ConditionalOnMissingBean(Clock.class)
        Clock operatorFrameworkClock() {
            return Clock.systemUTC();
        }

        @Bean(destroyMethod = "")
        @ConditionalOnMissingBean(KubernetesClient.class)
        KubernetesClient kubernetesClient(FrameworkKubernetesClientOwnership ownership) {
            var client = new KubernetesClientBuilder().build();
            ownership.own(client);
            return client;
        }

        @Bean
        ControllerRegistrationDiscovery controllerRegistrationDiscovery(
                ConfigurableListableBeanFactory beanFactory) {
            return new ControllerRegistrationDiscovery(beanFactory);
        }

        @Bean
        Fabric8ControllerRuntimeFactory controllerRuntimeFactory(
                KubernetesClient client,
                ControllerRegistrationDiscovery discovery,
                OperatorFrameworkProperties properties,
                Environment environment,
                OperatorFrameworkMetrics metrics) {
            try {
                var timeout = environment.getProperty(
                        "spring.lifecycle.timeout-per-shutdown-phase", Duration.class, Duration.ofSeconds(30));
                return new Fabric8ControllerRuntimeFactory(client, discovery.discover(), properties, timeout, metrics);
            } catch (RuntimeException exception) {
                throw new ApplicationContextException("controller mode configuration is invalid: "
                        + exception.getMessage(), exception);
            }
        }

        @Bean
        Fabric8LeaderElectionAdapter leaderElectionAdapter(
                KubernetesClient client,
                OperatorFrameworkProperties properties,
                Environment environment) {
            return new Fabric8LeaderElectionAdapter(client, properties, environment);
        }

        @Bean
        OperatorFrameworkLifecycle operatorFrameworkLifecycle(
                OperatorFrameworkProperties properties,
                Fabric8ControllerRuntimeFactory runtimeFactory,
                Fabric8LeaderElectionAdapter leaderElection,
                RuntimeLifecycleSupport support) {
            return new OperatorFrameworkLifecycle(properties, runtimeFactory, leaderElection, support);
        }

        @Bean
        ReconcileRetryAspect reconcileRetryAspect(
                OperatorFrameworkProperties properties,
                OperatorFrameworkMetrics metrics,
                ConfigurableListableBeanFactory beanFactory) {
            return new ReconcileRetryAspect(properties, metrics, beanFactory);
        }

        @Bean
        ReconcileRateLimitAspect reconcileRateLimitAspect(
                OperatorFrameworkProperties properties,
                Clock clock,
                ConfigurableListableBeanFactory beanFactory) {
            return new ReconcileRateLimitAspect(properties, clock, beanFactory);
        }

        @Bean
        RuntimeLifecycleSupport runtimeLifecycleSupport(
                RuntimeReadiness readiness,
                OperatorFrameworkMetrics metrics,
                ReconcileRetryAspect retry,
                ReconcileRateLimitAspect rateLimit) {
            return new RuntimeLifecycleSupport(readiness, metrics, retry, rateLimit);
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnProperty(prefix = "operator.framework.events", name = "enabled", matchIfMissing = true)
        @ConditionalOnMissingBean(KubernetesEventPublisher.class)
        KubernetesEventPublisher kubernetesEventPublisher(
                KubernetesClient client,
                OperatorFrameworkProperties properties,
                Environment environment,
                OperatorFrameworkMetrics metrics,
                Clock clock) {
            return new AggregatingKubernetesEventPublisher(client, properties, environment, clock, metrics);
        }
    }

    /** MVC webhook beans are absent in controller-only mode. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("'${operator.framework.mode:combined}'.equalsIgnoreCase('webhook')"
            + " || '${operator.framework.mode:combined}'.equalsIgnoreCase('combined')")
    static class WebhookSideConfiguration {
        @Bean
        WebhookCallbackRegistry webhookCallbackRegistry(ConfigurableListableBeanFactory beanFactory) {
            return new WebhookCallbackRegistry(beanFactory);
        }

        @Bean
        AdmissionWebhookController admissionWebhookController(
                WebhookCallbackRegistry registry,
                ObjectMapper objectMapper,
                OperatorFrameworkMetrics metrics) {
            return new AdmissionWebhookController(registry, objectMapper, metrics);
        }

        @Bean
        ConversionWebhookController conversionWebhookController(
                WebhookCallbackRegistry registry,
                ObjectMapper objectMapper,
                OperatorFrameworkMetrics metrics) {
            return new ConversionWebhookController(registry, objectMapper, metrics);
        }
    }
}
