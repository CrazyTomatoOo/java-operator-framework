/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.webhook;

import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookCallbackRegistryTest {
    @Test
    void resolvesDeclaredGenericAndRetainsOriginalBean() {
        try (var context = context("validator", TypedValidator.class, TypedValidator::new)) {
            var registry = new WebhookCallbackRegistry(context.getBeanFactory());

            var callback = registry.validator("validator").orElseThrow();
            assertThat(callback.resourceType()).isEqualTo(ConfigMap.class);
            assertThat(callback.bean()).isSameAs(context.getBean("validator"));
        }
    }

    @Test
    void resolvesTargetGenericWithoutUnwrappingJdkProxy() {
        var target = new TypedValidator();
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(false);
        var proxy = factory.getProxy();
        try (var context = context("proxied", AdmissionValidator.class, () -> (AdmissionValidator<?>) proxy)) {
            var registry = new WebhookCallbackRegistry(context.getBeanFactory());

            assertThat(registry.validator("proxied").orElseThrow().resourceType()).isEqualTo(ConfigMap.class);
            assertThat(registry.validator("proxied").orElseThrow().bean()).isSameAs(proxy);
        }
    }

    @Test
    void rejectsRawGenericAndUnsafeRouteName() {
        try (var raw = context("raw", RawValidator.class, RawValidator::new)) {
            assertThatThrownBy(() -> new WebhookCallbackRegistry(raw.getBeanFactory()))
                    .isInstanceOf(ApplicationContextException.class)
                    .hasMessageContaining("raw or unresolved");
        }
        try (var unsafe = context("bad/name", TypedValidator.class, TypedValidator::new)) {
            assertThatThrownBy(() -> new WebhookCallbackRegistry(unsafe.getBeanFactory()))
                    .isInstanceOf(ApplicationContextException.class)
                    .hasMessageContaining("safe URL segment");
        }
        try (var uppercase = context("echoValidator", TypedValidator.class, TypedValidator::new)) {
            assertThatThrownBy(() -> new WebhookCallbackRegistry(uppercase.getBeanFactory()))
                    .isInstanceOf(ApplicationContextException.class)
                    .hasMessageContaining("lowercase RFC 1123");
        }
    }

    @Test
    void tracksOnlySafeFailureMetadata() {
        try (var context = context("validator", TypedValidator.class, TypedValidator::new)) {
            var registry = new WebhookCallbackRegistry(context.getBeanFactory());
            registry.recordFailure("validator", "validator");

            assertThat(registry.lastFailure()).contains("validator callback 'validator' failed");
        }
    }

    private <T> GenericApplicationContext context(String name, Class<T> type, java.util.function.Supplier<T> bean) {
        var context = new GenericApplicationContext();
        context.registerBean(name, type, bean);
        context.refresh();
        return context;
    }

    static final class TypedValidator implements AdmissionValidator<ConfigMap> {
        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext context) {
            return AdmissionDecision.allow();
        }
    }

    @SuppressWarnings("rawtypes")
    static final class RawValidator implements AdmissionValidator {
        @Override
        public AdmissionDecision validate(HasMetadata current, AdmissionContext context) {
            return AdmissionDecision.allow();
        }
    }
}
