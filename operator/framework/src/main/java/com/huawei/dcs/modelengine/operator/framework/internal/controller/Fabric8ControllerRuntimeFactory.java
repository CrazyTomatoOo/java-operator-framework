package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.time.Duration;
import java.util.List;

/** Creates a new set of Fabric8 controller resources for every runtime term. */
public final class Fabric8ControllerRuntimeFactory implements ControllerRuntimeFactory {
    private final KubernetesClient client;
    private final List<ControllerRegistration<?>> registrations;
    private final OperatorFrameworkProperties properties;
    private final Duration shutdownTimeout;
    private final OperatorFrameworkMetrics metrics;

    public Fabric8ControllerRuntimeFactory(
            KubernetesClient client,
            List<ControllerRegistration<?>> registrations,
            OperatorFrameworkProperties properties,
            Duration shutdownTimeout,
            OperatorFrameworkMetrics metrics) {
        this.client = client;
        this.registrations = List.copyOf(registrations);
        this.properties = properties;
        this.shutdownTimeout = shutdownTimeout;
        this.metrics = metrics;
    }

    Fabric8ControllerRuntimeFactory(
            KubernetesClient client,
            List<ControllerRegistration<?>> registrations,
            OperatorFrameworkProperties properties,
            Duration shutdownTimeout) {
        this(client, registrations, properties, shutdownTimeout, new OperatorFrameworkMetrics(null));
    }

    @Override
    public ControllerRuntime create() {
        var runtimes = registrations.stream()
                .map(registration -> new Fabric8Controller<>(
                        client, registration, properties.getController(), shutdownTimeout, metrics))
                .map(ControllerRuntime.class::cast)
                .toList();
        return new RuntimeGroup(runtimes);
    }

    private static final class RuntimeGroup implements ControllerRuntime {
        private final List<ControllerRuntime> runtimes;

        private RuntimeGroup(List<ControllerRuntime> runtimes) {
            this.runtimes = runtimes;
        }

        @Override
        public void start() {
            try {
                runtimes.forEach(ControllerRuntime::start);
            } catch (RuntimeException exception) {
                runtimes.forEach(ControllerRuntime::stop);
                throw exception;
            }
        }

        @Override
        public boolean isReady() {
            return runtimes.stream().allMatch(ControllerRuntime::isReady);
        }

        @Override
        public boolean isRunning() {
            return runtimes.stream().allMatch(ControllerRuntime::isRunning);
        }

        @Override
        public int queueDepth() {
            return runtimes.stream().mapToInt(ControllerRuntime::queueDepth).sum();
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> stop() {
            var futures = runtimes.stream()
                    .map(ControllerRuntime::stop)
                    .map(java.util.concurrent.CompletionStage::toCompletableFuture)
                    .toArray(java.util.concurrent.CompletableFuture[]::new);
            return java.util.concurrent.CompletableFuture.allOf(futures);
        }
    }
}
