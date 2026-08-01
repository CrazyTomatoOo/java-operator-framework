/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.leader;

import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElector;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import org.springframework.core.env.Environment;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * Fabric8 Lease-backed leader election.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public final class Fabric8LeaderElectionAdapter implements LeaderElectionAdapter {
    private final KubernetesClient client;
    private final OperatorFrameworkProperties.Controller controllerProperties;
    private final OperatorFrameworkProperties.LeaderElection properties;
    private final Environment environment;
    private LeaderElector elector;
    private CompletableFuture<?> election;

    public Fabric8LeaderElectionAdapter(
            KubernetesClient client,
            OperatorFrameworkProperties properties,
            Environment environment) {
        this.client = client;
        controllerProperties = properties.getController();
        this.properties = properties.getLeaderElection();
        this.environment = environment;
    }

    @Override
    public synchronized CompletionStage<Void> start(Runnable onStartLeading, Runnable onStopLeading) {
        if (elector == null) {
            elector = client.leaderElector().withConfig(config(onStartLeading, onStopLeading)).build();
            election = elector.start();
        }
        return election.thenApply(ignored -> null);
    }

    @Override
    public synchronized void stop() {
        if (elector != null) {
            if (election != null) {
                election.cancel(true);
            }
            election = null;
            elector = null;
        }
    }

    LeaderElectionConfig config(Runnable onStartLeading, Runnable onStopLeading) {
        var name = leaseName();
        var callbacks = new LeaderCallbacks(onStartLeading, onStopLeading, ignored -> { });
        var lock = new LeaseLock(namespace(), name, identity());
        return new LeaderElectionConfigBuilder()
                .withName(name)
                .withLock(lock)
                .withLeaseDuration(properties.getLeaseDuration())
                .withRenewDeadline(properties.getRenewDeadline())
                .withRetryPeriod(properties.getRetryPeriod())
                .withReleaseOnCancel(true)
                .withLeaderCallbacks(callbacks)
                .build();
    }

    private String leaseName() {
        var configured = properties.getLeaseName();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        var application = environment.getProperty("spring.application.name", "operator-framework");
        var sanitized = application.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "-")
                .replaceAll("^-+|-+$", "");
        if (sanitized.length() > 56) {
            sanitized = sanitized.substring(0, 56).replaceAll("-+$", "");
        }
        return (sanitized.isBlank() ? "operator-framework" : sanitized) + "-leader";
    }

    private String namespace() {
        return Stream.of(properties.getNamespace(), controllerProperties.getNamespace(), client.getNamespace())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("default");
    }

    private String identity() {
        var hostname = System.getenv("HOSTNAME");
        return hostname == null || hostname.isBlank()
                ? ManagementFactory.getRuntimeMXBean().getName()
                : hostname;
    }
}
