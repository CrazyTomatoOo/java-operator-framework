/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;

import io.fabric8.kubernetes.client.KubernetesClient;

import java.time.Duration;
import java.util.List;

/**
 * Creates a new set of Fabric8 controller resources for every runtime instance.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class Fabric8ControllerRuntimeFactory implements ControllerRuntimeFactory {
    private final KubernetesClient client;

    private final List<ControllerRegistration<?>> registrations;

    private final OperatorFrameworkProperties properties;

    private final Duration shutdownTimeout;

    private final OperatorFrameworkMetrics metrics;

    /**
     * Creates a factory that builds a Fabric8 runtime per registration.
     *
     * @param client the Kubernetes client shared by all controllers
     * @param registrations the controller registrations to run
     * @param properties the operator framework configuration
     * @param shutdownTimeout how long to wait for workers to finish on stop
     * @param metrics the metrics sink for controller gauges and counters
     */
    public Fabric8ControllerRuntimeFactory(KubernetesClient client, List<ControllerRegistration<?>> registrations,
        OperatorFrameworkProperties properties, Duration shutdownTimeout, OperatorFrameworkMetrics metrics) {
        this.client = client;
        this.registrations = List.copyOf(registrations);
        this.properties = properties;
        this.shutdownTimeout = shutdownTimeout;
        this.metrics = metrics;
    }

    Fabric8ControllerRuntimeFactory(KubernetesClient client, List<ControllerRegistration<?>> registrations,
        OperatorFrameworkProperties properties, Duration shutdownTimeout) {
        this(client, registrations, properties, shutdownTimeout, new OperatorFrameworkMetrics(null));
    }

    /**
     * Creates one {@link Fabric8Controller} per registration, grouped into a single runtime.
     *
     * @return a runtime that manages all controller runtimes as a group
     */
    @Override
    public ControllerRuntime create() {
        var runtimes = registrations.stream()
            .map(registration -> new Fabric8Controller<>(client, registration, properties.getController(),
                shutdownTimeout, metrics))
            .map(ControllerRuntime.class::cast)
            .toList();
        return new RuntimeGroup(runtimes);
    }

    private static final class RuntimeGroup implements ControllerRuntime {
        private final List<ControllerRuntime> runtimes;

        private RuntimeGroup(List<ControllerRuntime> runtimes) {
            this.runtimes = runtimes;
        }

        /**
         * Starts every runtime in the group, stopping all of them when one fails.
         *
         * @throws RuntimeException when any runtime fails to start
         */
        @Override
        public void start() {
            try {
                runtimes.forEach(ControllerRuntime::start);
            } catch (RuntimeException exception) {
                runtimes.forEach(ControllerRuntime::stop);
                throw exception;
            }
        }

        /**
         * Reports whether every runtime in the group is ready.
         *
         * @return {@code true} when all runtimes are ready
         */
        @Override
        public boolean isReady() {
            return runtimes.stream().allMatch(ControllerRuntime::isReady);
        }

        /**
         * Reports whether every runtime in the group is running.
         *
         * @return {@code true} when all runtimes are running
         */
        @Override
        public boolean isRunning() {
            return runtimes.stream().allMatch(ControllerRuntime::isRunning);
        }

        /**
         * Returns the summed queue depth over all runtimes in the group.
         *
         * @return the total queue depth
         */
        @Override
        public int queueDepth() {
            return runtimes.stream().mapToInt(ControllerRuntime::queueDepth).sum();
        }

        /**
         * Stops every runtime in the group.
         *
         * @return a stage that completes when all runtimes have stopped
         */
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
