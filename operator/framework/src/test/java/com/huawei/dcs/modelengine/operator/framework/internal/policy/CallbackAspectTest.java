package com.huawei.dcs.modelengine.operator.framework.internal.policy;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionMutator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.MutationResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ResourceConverter;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackAspectTest {
    @Test
    void observesSuccessAndExceptionForEveryExtensionPointWithoutRetryingWebhook() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var resource = resource();
            context.getBean(Reconciler.class).reconcile(
                    resource, ReconciliationContext.<ConfigMap>withoutCache(new ResourceKey("default", "sample"), List.of()));
            context.getBean("validator", AdmissionValidator.class).validate(resource, null);
            context.getBean("mutator", AdmissionMutator.class).mutate(resource, null);
            context.getBean(ResourceConverter.class).convert(resource, new ConversionContext("v1", "v2"));
            var throwing = context.getBean("throwingValidator", AdmissionValidator.class);
            assertThatThrownBy(() -> throwing.validate(resource, null)).hasMessage("sensitive");

            var registry = context.getBean(SimpleMeterRegistry.class);
            assertMetric(registry, "reconciler", "reconciler", "requeue");
            assertMetric(registry, "validator", "validator", "denied");
            assertMetric(registry, "mutator", "mutator", "success");
            assertMetric(registry, "converter", "converter", "failure");
            assertMetric(registry, "validator", "throwingValidator", "error");
            var target = (ThrowingValidator) org.springframework.test.util.AopTestUtils
                    .getTargetObject(context.getBean(ThrowingValidator.class));
            assertThat(target.calls).hasValue(1);
        }
    }

    @Test
    void identifiesEachMethodOnMultiInterfaceCallback() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var callback = context.getBean(DualCallback.class);
            callback.validate(resource(), null);
            callback.mutate(resource(), null);

            var registry = context.getBean(SimpleMeterRegistry.class);
            assertMetric(registry, "validator", "dualCallback", "success");
            assertMetric(registry, "mutator", "dualCallback", "success");
        }
    }

    @Test
    void aspectsHaveStableFrameworkOrderAndPoliciesStayReconcilerOnly() {
        assertThat(OrderUtils.getOrder(ReconcileObservationAspect.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
        assertThat(OrderUtils.getOrder(ReconcileExceptionAspect.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 200);
        assertThat(OrderUtils.getOrder(ReconcileRetryAspect.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);
        assertThat(OrderUtils.getOrder(ReconcileRateLimitAspect.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 400);
    }

    private void assertMetric(SimpleMeterRegistry registry, String type, String bean, String outcome) {
        assertThat(registry.get("operator.framework.callback.total")
                .tags("callback.type", type, "bean", bean, "outcome", outcome).counter().count()).isEqualTo(1);
        assertThat(registry.get("operator.framework.callback.duration")
                .tags("callback.type", type, "bean", bean, "outcome", outcome).timer().count()).isEqualTo(1);
    }

    private ConfigMap resource() {
        return new ConfigMapBuilder().withApiVersion("v1").withKind("ConfigMap")
                .withNewMetadata().withNamespace("default").withName("sample").endMetadata().build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        OperatorFrameworkMetrics metrics(SimpleMeterRegistry registry) {
            return new OperatorFrameworkMetrics(registry);
        }

        @Bean
        ReconcileObservationAspect observation(
                OperatorFrameworkMetrics metrics,
                ConfigurableListableBeanFactory beanFactory) {
            return new ReconcileObservationAspect(ObservationRegistry.NOOP, metrics, beanFactory);
        }

        @Bean
        ReconcileExceptionAspect exceptions(
                OperatorFrameworkMetrics metrics,
                ConfigurableListableBeanFactory beanFactory) {
            return new ReconcileExceptionAspect(metrics, beanFactory);
        }

        @Bean
        ReconcileRetryAspect retry(
                OperatorFrameworkMetrics metrics,
                ConfigurableListableBeanFactory beanFactory) {
            return new ReconcileRetryAspect(new OperatorFrameworkProperties(), metrics, beanFactory, () -> 1.0);
        }

        @Bean
        ReconcileRateLimitAspect rateLimit() {
            return new ReconcileRateLimitAspect(new OperatorFrameworkProperties(), java.time.Clock.systemUTC());
        }

        @Bean
        TypedReconciler reconciler() {
            return new TypedReconciler();
        }

        @Bean
        TypedValidator validator() {
            return new TypedValidator();
        }

        @Bean
        ThrowingValidator throwingValidator() {
            return new ThrowingValidator();
        }

        @Bean
        TypedMutator mutator() {
            return new TypedMutator();
        }

        @Bean
        DualCallback dualCallback() {
            return new DualCallback();
        }

        @Bean
        TypedConverter converter() {
            return new TypedConverter();
        }
    }

    static class TypedReconciler implements Reconciler<ConfigMap> {
        @Override
        public ReconcileResult reconcile(ConfigMap resource, ReconciliationContext<ConfigMap> context) {
            return ReconcileResult.requeueNow();
        }
    }

    static class TypedValidator implements AdmissionValidator<ConfigMap> {
        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext context) {
            return AdmissionDecision.deny("policy denied");
        }
    }

    static class ThrowingValidator extends TypedValidator {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext context) throws RuntimeException {
            calls.incrementAndGet();
            throw new RuntimeException("sensitive");
        }
    }

    static class TypedMutator implements AdmissionMutator<ConfigMap> {
        @Override
        public MutationResult<ConfigMap> mutate(ConfigMap current, AdmissionContext context) {
            return MutationResult.unchanged();
        }
    }

    static class DualCallback implements AdmissionValidator<ConfigMap>, AdmissionMutator<ConfigMap> {
        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext context) {
            return AdmissionDecision.allow();
        }

        @Override
        public MutationResult<ConfigMap> mutate(ConfigMap current, AdmissionContext context) {
            return MutationResult.unchanged();
        }
    }

    static class TypedConverter implements ResourceConverter<ConfigMap> {
        @Override
        public ConversionResult<ConfigMap> convert(ConfigMap resource, ConversionContext context) {
            return ConversionResult.failed("conversion failed");
        }
    }
}
