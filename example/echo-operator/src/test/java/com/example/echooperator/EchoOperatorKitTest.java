/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.echooperator;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import com.huawei.dcs.modelengine.operator.framework.testing.OperatorTestKit;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** End-to-end: real controller runtime against the in-memory API server from the testing kit. */
class EchoOperatorKitTest {
    @Test
    void echoesMessageIntoOwnedChildThroughRealRuntime() throws Exception {
        try (var kit = OperatorTestKit.create()) {
            var namespace = kit.client().getNamespace();
            var reconciler = new EchoReconciler(kit.client(), new KubernetesEventPublisher() {
                /**
                 * Ignores normal events.
                 *
                 * @param involvedObject the object the event is about
                 * @param reason the event reason
                 * @param message the event message
                 */
                @Override
                public void normal(HasMetadata involvedObject, String reason, String message) {
                }

                /**
                 * Ignores warning events.
                 *
                 * @param involvedObject the object the event is about
                 * @param reason the event reason
                 * @param message the event message
                 */
                @Override
                public void warning(HasMetadata involvedObject, String reason, String message) {
                }
            });
            var registration = ControllerBuilder.forResource(ConfigMap.class, reconciler)
                    .labelSelector(Map.of(EchoReconciler.ENABLED_LABEL, "true"))
                    .indexField(EchoReconciler.INDEX_ECHO_TARGET, EchoReconciler::echoTargetName)
                    .owns(ConfigMap.class)
                    .build();
            var runtime = kit.controller(registration);
            runtime.start();

            kit.client().configMaps().inNamespace(namespace).resource(new ConfigMapBuilder()
                    .withNewMetadata().withName("source").withUid("uid-1")
                    .addToLabels(EchoReconciler.ENABLED_LABEL, "true").endMetadata()
                    .addToData(EchoReconciler.MESSAGE_KEY, "hello").build()).create();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var echo = kit.client().configMaps().inNamespace(namespace).withName("source-echo").get();
                assertThat(echo).isNotNull();
                assertThat(echo.getData()).containsEntry(EchoReconciler.MESSAGE_KEY, "HELLO");
            });

            // update path: the child lookup must hit the owns() cache, then apply the new message
            kit.client().configMaps().inNamespace(namespace).resource(new ConfigMapBuilder()
                    .withNewMetadata().withName("source").withUid("uid-1")
                    .addToLabels(EchoReconciler.ENABLED_LABEL, "true").endMetadata()
                    .addToData(EchoReconciler.MESSAGE_KEY, "world").build()).update();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var echo = kit.client().configMaps().inNamespace(namespace).withName("source-echo").get();
                assertThat(echo.getData()).containsEntry(EchoReconciler.MESSAGE_KEY, "WORLD");
            });
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }
}
