/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkHealthIndicator;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.OperatorFrameworkLifecycle;
import com.huawei.dcs.modelengine.operator.framework.internal.webhook.AdmissionWebhookController;
import com.huawei.dcs.modelengine.operator.framework.internal.webhook.ConversionWebhookController;
import com.huawei.dcs.modelengine.operator.framework.internal.webhook.WebhookCallbackRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OperatorFrameworkAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(AopAutoConfiguration.class, OperatorFrameworkAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void disabledCreatesNothing() {
        runner.withPropertyValues("operator.framework.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OperatorFrameworkProperties.class);
            assertThat(context).doesNotHaveBean(OperatorFrameworkLifecycle.class);
            assertThat(context).doesNotHaveBean(WebhookCallbackRegistry.class);
            assertThat(context).doesNotHaveBean(OperatorFrameworkHealthIndicator.class);
            assertThat(context).doesNotHaveBean(KubernetesClient.class);
        });
    }

    @Test
    void controllerModeRequiresControllerAndCreatesNoMvcWebhooks() {
        runner.withPropertyValues("operator.framework.mode=controller").run(context -> assertThat(context).hasFailed());
        controllerRunner().run(context -> {
            assertThat(context).hasSingleBean(OperatorFrameworkLifecycle.class);
            assertThat(context).doesNotHaveBean(WebhookCallbackRegistry.class);
            assertThat(context).doesNotHaveBean(AdmissionWebhookController.class);
            assertThat(context).doesNotHaveBean(ConversionWebhookController.class);
        });
    }

    private ApplicationContextRunner controllerRunner() {
        return baseInfrastructure().withBean("configmapreconciler", ConfigMapReconciler.class, ConfigMapReconciler::new)
            .withPropertyValues("operator.framework.mode=controller");
    }

    private ApplicationContextRunner baseInfrastructure() {
        return runner.withBean(KubernetesClient.class, () -> mock(KubernetesClient.class),
            definition -> definition.setDestroyMethodName(""));
    }

    @Test
    void webhookModeRequiresCallbackAndCreatesNoControllerInfrastructure() {
        runner.withPropertyValues("operator.framework.mode=webhook").run(context -> assertThat(context).hasFailed());
        webhookRunner().run(context -> {
            assertThat(context).hasSingleBean(WebhookCallbackRegistry.class);
            assertThat(context).hasSingleBean(AdmissionWebhookController.class);
            assertThat(context).hasSingleBean(ConversionWebhookController.class);
            assertThat(context).doesNotHaveBean(KubernetesClient.class);
            assertThat(context).doesNotHaveBean(OperatorFrameworkLifecycle.class);
            assertThat(context).doesNotHaveBean(KubernetesEventPublisher.class);
        });
    }

    private ApplicationContextRunner webhookRunner() {
        return runner.withBean("configmapvalidator", ConfigMapValidator.class, ConfigMapValidator::new)
            .withPropertyValues("operator.framework.mode=webhook");
    }

    @Test
    void combinedModeRequiresBothGroups() {
        controllerRunner().withPropertyValues("operator.framework.mode=combined")
            .run(context -> assertThat(context).hasFailed());
        webhookRunner().withPropertyValues("operator.framework.mode=combined")
            .run(context -> assertThat(context).hasFailed());
        baseInfrastructure().withBean("configmapreconciler", ConfigMapReconciler.class, ConfigMapReconciler::new)
            .withBean("configmapvalidator", ConfigMapValidator.class, ConfigMapValidator::new)
            .withPropertyValues("operator.framework.mode=combined")
            .run(context -> {
                assertThat(context).hasSingleBean(OperatorFrameworkLifecycle.class);
                assertThat(context).hasSingleBean(WebhookCallbackRegistry.class);
                assertThat(context).hasSingleBean(OperatorFrameworkHealthIndicator.class);
            });
    }

    @Test
    void contextDoesNotCloseUserSuppliedKubernetesClient() {
        var supplied = mock(KubernetesClient.class);
        runner.withBean(KubernetesClient.class, () -> supplied, definition -> definition.setDestroyMethodName(""))
            .withBean("configmapreconciler", ConfigMapReconciler.class, ConfigMapReconciler::new)
            .withPropertyValues("operator.framework.mode=controller")
            .run(context -> assertThat(context).hasSingleBean(KubernetesClient.class));

        verify(supplied, never()).close();
    }

    @Test
    void userSuppliedEventPublisherReplacesDefault() {
        var supplied = mock(KubernetesEventPublisher.class);
        controllerRunner().withBean(KubernetesEventPublisher.class, () -> supplied)
            .run(context -> assertThat(context).hasSingleBean(KubernetesEventPublisher.class));
    }

    static class ConfigMapReconciler implements Reconciler<ConfigMap> {
        /**
         * Reconciles the resource by doing nothing.
         *
         * @param resource the resource to reconcile
         * @param context the reconciliation context
         * @return a completed result
         */
        @Override
        public ReconcileResult reconcile(ConfigMap resource,
            com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext context) {
            return ReconcileResult.done();
        }
    }

    static class ConfigMapValidator implements AdmissionValidator<ConfigMap> {
        /**
         * Allows every admission request.
         *
         * @param current the resource under admission
         * @param context the admission context
         * @return an allow decision
         */
        @Override
        public AdmissionDecision validate(ConfigMap current,
            com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext context) {
            return AdmissionDecision.allow();
        }
    }
}
