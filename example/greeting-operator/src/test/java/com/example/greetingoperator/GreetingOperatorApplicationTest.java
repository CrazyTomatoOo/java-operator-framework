/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.mockito.Mockito.mock;

import java.time.Duration;

/** Verifies Spring discovers all greeting beans and applies the advanced registration options. */
class GreetingOperatorApplicationTest {
    @Test
    void contextDiscoversCallbacksAndRegistrationOptions() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class,
                        ServletWebServerFactoryAutoConfiguration.class,
                        com.huawei.dcs.modelengine.operator.framework.autoconfigure
                                .OperatorFrameworkAutoConfiguration.class))
                .withUserConfiguration(GreetingOperatorApplication.class)
                .withBean(KubernetesClient.class, () -> mock(KubernetesClient.class),
                        definition -> definition.setDestroyMethodName(""))
                .withPropertyValues("operator.framework.mode=combined")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GreetingReconciler.class);
                    assertThat(context).hasSingleBean(GreetingConverter.class);
                    assertThat(context).hasSingleBean(GreetingConfigMap.class);
                    assertThat(context).hasSingleBean(ControllerRegistration.class);

                    var registration = context.getBean(ControllerRegistration.class);
                    assertThat(registration.resourceType()).isEqualTo(Greeting.class);
                    assertThat(registration.generationFilter()).hasValue(true);
                    assertThat(registration.resyncPeriod()).hasValue(Duration.ofMinutes(2));
                    assertThat(registration.ownedResources()).contains(ConfigMap.class);
                    assertThat(registration.secondaryWatches())
                            .extracting(watch -> ((ControllerRegistration.SecondaryWatch<?, ?>) watch).name())
                            .containsExactly("styles");
                    assertThat(registration.watchesKubernetesEvents()).isFalse();
                });
    }
}