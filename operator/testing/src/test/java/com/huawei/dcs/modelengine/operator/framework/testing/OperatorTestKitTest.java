/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.testing;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.Mappers;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.ControllerRuntime;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
        assertThat(context.cache().getByKey("default/cm")).isSameAs(configMap);
        assertThat(context.cacheFor(ConfigMap.class).getByKey("default/cm")).isSameAs(configMap);
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

    @Test
    void controllerRequiresAtLeastOneRegistration() {
        try (var kit = OperatorTestKit.create()) {
            assertThatThrownBy(kit::controller).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void closePropagatesRuntimeStopFailureAfterTearingDownServer() throws Exception {
        var kit = OperatorTestKit.create();
        var failing = new ControllerRuntime() {
            @Override
            public void start() {
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public CompletionStage<Void> stop() {
                var future = new CompletableFuture<Void>();
                future.completeExceptionally(new IllegalStateException("boom"));
                return future;
            }
        };
        addRuntime(kit, failing);

        assertThatThrownBy(kit::close)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("failed to stop one or more test runtimes")
            .hasRootCauseMessage("boom");
    }

    private static void addRuntime(OperatorTestKit kit, ControllerRuntime runtime) throws Exception {
        var field = OperatorTestKit.class.getDeclaredField("runtimes");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var runtimes = (List<ControllerRuntime>) field.get(kit);
        runtimes.add(runtime);
    }
}
