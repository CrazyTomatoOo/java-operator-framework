/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.controller.Mappers;

import io.fabric8.kubernetes.api.model.ConfigMap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Spring Boot entry point for the greeting operator example.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@SpringBootApplication
public class GreetingOperatorApplication {
    /**
     * Starts the greeting operator Spring Boot application.
     *
     * @param args the command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(GreetingOperatorApplication.class, args);
    }

    /**
     * Registers the greeting controller with the advanced options exercised by this example:
     * generation-filtered reconciliation, a periodic resync, a managed child ConfigMap, and a
     * watched styles ConfigMap that re-renders every affected greeting when it changes.
     *
     * @param reconciler the greeting reconciler bean
     * @param child the managed dependent bean that computes the child ConfigMap
     * @return the controller registration
     */
    @Bean
    ControllerRegistration<Greeting> greetingController(GreetingReconciler reconciler, GreetingConfigMap child) {
        return ControllerBuilder.forResource(Greeting.class, reconciler)
                .generationFilter(true)
                .resyncPeriod(Duration.ofMinutes(2))
                .manages(child)
                .watches("styles", ConfigMap.class, Mappers.byLabel(GreetingReconciler.STYLE_LABEL))
                .build();
    }
}