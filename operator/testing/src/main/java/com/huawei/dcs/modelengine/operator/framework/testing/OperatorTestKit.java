/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.testing;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationTrigger;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.TriggerRole;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.ControllerRuntime;
import com.huawei.dcs.modelengine.operator.framework.internal.controller.Fabric8ControllerRuntimeFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.impl.cache.CacheImpl;
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test kit for operator integration tests: an in-memory CRUD API server, a fake client, and
 * helpers to drive a real controller runtime or to build cache-backed reconciliation contexts.
 *
 * <p>Typical use:
 * <pre>{@code
 * try (var kit = OperatorTestKit.create()) {
 *     var runtime = kit.controller(registration);
 *     runtime.start();
 *     kit.client().configMaps().inNamespace("default").resource(configMap).create();
 *     // await effects through kit.client()
 * }
 * }</pre>
 *
 * <p>Operators that watch Kubernetes Events must disable involvedObject field-selector filtering
 * ({@code properties.getController().setFilterEventsByInvolvedObject(false)}), which the in-memory
 * server cannot match.
 *
 * @author z00919064 zhangshijie
 * @since 2026-08-01
 */
public final class OperatorTestKit implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final KubernetesMockServer server;
    private final KubernetesClient client;
    private final List<ControllerRuntime> runtimes = new CopyOnWriteArrayList<>();

    private OperatorTestKit(KubernetesMockServer server, KubernetesClient client) {
        this.server = server;
        this.client = client;
    }

    /**
     * Starts an in-memory CRUD API server; close the kit to tear it down.
     *
     * @return a ready test kit backed by the in-memory server
     */
    public static OperatorTestKit create() {
        var server = new KubernetesMockServer(new Context(new ObjectMapper()), new MockWebServer(),
                new HashMap<>(), new KubernetesMixedDispatcher(new HashMap<>()), false);
        server.init();
        return new OperatorTestKit(server, server.createClient());
    }

    /**
     * Fake client backed by the in-memory server.
     *
     * @return the Kubernetes client backed by the in-memory server
     */
    public KubernetesClient client() {
        return client;
    }

    /**
     * Real controller runtime (informers, workers, queue) with default properties.
     *
     * @param registrations the controller registrations to run
     * @return the started controller runtime
     */
    public ControllerRuntime controller(ControllerRegistration<?>... registrations) {
        return controller(new OperatorFrameworkProperties(), registrations);
    }

    /**
     * Real controller runtime with explicit properties; stopped automatically on close.
     *
     * @param properties the framework properties to apply
     * @param registrations the controller registrations to run
     * @return the started controller runtime
     */
    public ControllerRuntime controller(
            OperatorFrameworkProperties properties,
            ControllerRegistration<?>... registrations) {
        var runtime = new Fabric8ControllerRuntimeFactory(client, List.of(registrations), properties,
                SHUTDOWN_TIMEOUT, new OperatorFrameworkMetrics(null)).create();
        runtimes.add(runtime);
        return runtime;
    }

    // CacheImpl is fabric8's impl-jar indexer — the only concrete one shipped
    /**
     * Direct-invocation test context for {@code primary} with a working primary cache (the primary
     * itself is cached), so reconcilers can exercise by-index/get-by-key paths without a runtime.
     * For secondary caches, run a real runtime via {@link #controller} instead.
     *
     * @param <T> the primary resource type
     * @param primary the resource under reconciliation, also stored in the context cache
     * @return a reconciliation context whose primary cache contains {@code primary}
     */
    public static <T extends HasMetadata> ReconciliationContext<T> context(T primary) {
        var cache = new CacheImpl<T>();
        cache.put(primary);
        var reference = ResourceReference.from(primary);
        var trigger = new ReconciliationTrigger(ResourceEventType.ADDED, TriggerRole.PRIMARY, reference);
        return new ReconciliationContext<>(reference.key(), List.of(trigger), cache,
                Map.of(primary.getClass(), cache));
    }

    /** Stops all started runtimes and tears down the in-memory server. */
    @Override
    public void close() {
        runtimes.forEach(runtime -> runtime.stop().toCompletableFuture().join());
        client.close();
        server.destroy();
    }
}
