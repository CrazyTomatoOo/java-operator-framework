/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.testing;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.Mappers;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

class OperatorTestKitTest {
    @Test
    void contextSeedsPrimaryCacheForDirectReconcilerTests() {
        var configMap = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("default").withName("cm").withUid("uid-1").endMetadata()
                .build();

        var context = OperatorTestKit.context(configMap);

        assertThat(context.resourceKey().name()).isEqualTo("cm");
        assertThat(context.cache().getByKey("default/cm")).isNotNull();
        assertThat(context.cacheFor(ConfigMap.class).getByKey("default/cm")).isNotNull();
    }

    @Test
    void controllerRuntimeReconcilesAndExposesSecondaryCaches() throws Exception {
        try (var kit = OperatorTestKit.create()) {
            var namespace = kit.client().getNamespace();
            var hits = new CopyOnWriteArrayList<Boolean>();
            var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
                hits.add(context.cacheFor(Secret.class).getByKey(namespace + "/secondary") != null);
                return ReconcileResult.done();
            }).watches("secrets", Secret.class, Mappers.byLabel("primary")).build();
            var runtime = kit.controller(registration);
            runtime.start();

            kit.client().configMaps().inNamespace(namespace).resource(new ConfigMapBuilder()
                    .withNewMetadata().withName("sample").withUid("uid-1").endMetadata().build()).create();
            kit.client().secrets().inNamespace(namespace).resource(new SecretBuilder()
                    .withNewMetadata().withName("secondary").addToLabels("primary", "sample").endMetadata()
                    .build()).create();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(hits).contains(true));
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeStopsRuntimesAndServer() {
        var kit = OperatorTestKit.create();
        var registration = ControllerBuilder.forResource(ConfigMap.class,
                (resource, context) -> ReconcileResult.done()).build();
        var runtime = kit.controller(registration);
        runtime.start();
        await().atMost(Duration.ofSeconds(5)).until(runtime::isReady);

        kit.close();

        assertThat(runtime.isRunning()).isFalse();
    }
}
