package com.example.echooperator;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import io.fabric8.kubernetes.api.model.ConfigMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class EchoOperatorApplication {
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
