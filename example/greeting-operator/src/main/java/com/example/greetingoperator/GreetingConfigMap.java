/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.DependentResource;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import org.springframework.stereotype.Component;

/**
 * The owned ConfigMap that carries the rendered greeting message.
 *
 * <p>{@code Dependents.apply} stamps the controller owner reference onto the desired state,
 * server-side-applies it, and Kubernetes garbage-collects the child with its {@code Greeting}.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@Component
public class GreetingConfigMap implements DependentResource<ConfigMap, Greeting> {
    static final String DATA_KEY = "message";

    @Override
    public Class<ConfigMap> resourceType() {
        return ConfigMap.class;
    }

    @Override
    public ConfigMap desired(Greeting primary, ReconciliationContext<Greeting> context) {
        var metadata = primary.getMetadata();
        var rendered = GreetingReconciler.renderedMessage(primary, context);
        return new ConfigMapBuilder()
                .withNewMetadata()
                .withName(metadata.getName() + "-child")
                .withNamespace(metadata.getNamespace())
                .withLabels(java.util.Map.of(
                        "app.kubernetes.io/managed-by", "greeting-operator",
                        "greetings.example.com/role", "rendered"))
                .endMetadata()
                .addToData(DATA_KEY, rendered)
                .build();
    }
}