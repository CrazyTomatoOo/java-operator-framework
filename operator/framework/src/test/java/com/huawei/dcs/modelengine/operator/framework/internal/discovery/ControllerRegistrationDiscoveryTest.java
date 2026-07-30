package com.huawei.dcs.modelengine.operator.framework.internal.discovery;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControllerRegistrationDiscoveryTest {
    @Test
    void rejectsLambdaEvenWhenBeanMethodDeclaresItsGenericType() {
        try (var context = context(DeclaredGenericConfiguration.class)) {
            assertThatThrownBy(() -> discovery(context).discover())
                    .hasMessageContaining("lambda")
                    .hasMessageContaining("explicit ControllerRegistration");
        }
    }

    @Test
    void resolvesAopTargetButKeepsOriginalProxy() throws Exception {
        try (var context = context(ProxiedConfiguration.class)) {
            var registration = discovery(context).discover().getFirst();
            var proxy = context.getBean("proxiedReconciler", Reconciler.class);

            assertThat(registration.resourceType()).isEqualTo(ConfigMap.class);
            assertThat(registration.reconciler()).isSameAs(proxy);
            proxy.reconcile(new ConfigMap(), reconciliationContext());
            assertThat(ProxiedConfiguration.target.calls).hasValue(1);
        }
    }

    @Test
    void explicitRegistrationOverridesAutomaticRegistrationForResource() {
        try (var context = context(ExplicitConfiguration.class)) {
            var registration = discovery(context).discover().getFirst();

            assertThat(discovery(context).discover()).hasSize(1);
            assertThat(registration.reconciler()).isSameAs(context.getBean("explicitReconciler"));
        }
    }

    @Test
    void duplicateAutomaticRegistrationsFailWithBeanNames() {
        try (var context = context(DuplicateConfiguration.class)) {
            assertThatThrownBy(() -> discovery(context).discover())
                    .hasMessageContaining("firstReconciler")
                    .hasMessageContaining("secondReconciler");
        }
    }

    @Test
    void rawLambdaReconcilerFailsWithBeanName() {
        try (var context = context(RawConfiguration.class)) {
            assertThatThrownBy(() -> discovery(context).discover())
                    .hasMessageContaining("rawReconciler")
                    .hasMessageContaining("lambda");
        }
    }

    @Test
    void explicitRegistrationRequiresSpringManagedReconciler() {
        try (var context = context(NonBeanRegistrationConfiguration.class)) {
            assertThatThrownBy(() -> discovery(context).discover())
                    .hasMessageContaining("registration")
                    .hasMessageContaining("not a Spring bean");
        }
    }

    private ControllerRegistrationDiscovery discovery(AnnotationConfigApplicationContext context) {
        return new ControllerRegistrationDiscovery(context.getBeanFactory());
    }

    private AnnotationConfigApplicationContext context(Class<?> configuration) {
        return new AnnotationConfigApplicationContext(configuration);
    }

    private ReconciliationContext reconciliationContext() {
        return ReconciliationContext.<ConfigMap>withoutCache(
                new com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey("test", "sample"),
                java.util.List.of());
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclaredGenericConfiguration {
        @Bean
        Reconciler<Secret> secretReconciler() {
            return (resource, context) -> ReconcileResult.done();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProxiedConfiguration {
        private static CountingConfigMapReconciler target;

        @Bean
        @SuppressWarnings("rawtypes")
        Reconciler proxiedReconciler() {
            target = new CountingConfigMapReconciler();
            return (Reconciler) new ProxyFactory(target).getProxy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExplicitConfiguration {
        @Bean
        ConfigMapReconciler explicitReconciler() {
            return new ConfigMapReconciler();
        }

        @Bean
        SecondConfigMapReconciler automaticReconciler() {
            return new SecondConfigMapReconciler();
        }

        @Bean
        ControllerRegistration<ConfigMap> registration(ConfigMapReconciler explicitReconciler) {
            return ControllerBuilder.forResource(ConfigMap.class, explicitReconciler).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateConfiguration {
        @Bean
        ConfigMapReconciler firstReconciler() {
            return new ConfigMapReconciler();
        }

        @Bean
        SecondConfigMapReconciler secondReconciler() {
            return new SecondConfigMapReconciler();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RawConfiguration {
        @Bean
        @SuppressWarnings({"rawtypes", "unchecked"})
        Reconciler rawReconciler() {
            return (resource, context) -> ReconcileResult.done();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonBeanRegistrationConfiguration {
        @Bean
        ControllerRegistration<ConfigMap> registration() {
            return ControllerBuilder.forResource(ConfigMap.class, new ConfigMapReconciler()).build();
        }
    }

    static class ConfigMapReconciler implements Reconciler<ConfigMap> {
        @Override
        public ReconcileResult reconcile(ConfigMap resource, ReconciliationContext<ConfigMap> context) {
            return ReconcileResult.done();
        }
    }

    static final class SecondConfigMapReconciler extends ConfigMapReconciler {
    }

    static final class CountingConfigMapReconciler extends ConfigMapReconciler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ReconcileResult reconcile(ConfigMap resource, ReconciliationContext<ConfigMap> context) {
            calls.incrementAndGet();
            return ReconcileResult.done();
        }
    }
}
