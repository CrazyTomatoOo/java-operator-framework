/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.echooperator;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;

import io.fabric8.kubernetes.api.model.ConfigMap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Spring Boot entry point for the echo operator example.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@SpringBootApplication
public class EchoOperatorApplication {
    /**
     * Starts the echo operator Spring Boot application.
     *
     * @param args the command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(EchoOperatorApplication.class, args);
    }

    @Bean
    ControllerRegistration<ConfigMap> echoController(EchoReconciler reconciler) {
        return ControllerBuilder.forResource(ConfigMap.class, reconciler)
                .labelSelector(Map.of(EchoReconciler.ENABLED_LABEL, "true"))
                .indexField(EchoReconciler.INDEX_ECHO_TARGET, EchoReconciler::echoTargetName)
                .owns(ConfigMap.class)
                .build();
    }
}
