/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.RuntimeReadiness;
import com.huawei.dcs.modelengine.operator.framework.internal.leader.LeaderElectionAdapter;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.SmartLifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-blocking supervisor for controller startup, retries, leadership terms, and shutdown.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
@Slf4j
public final class OperatorFrameworkLifecycle implements SmartLifecycle {

    private final OperatorFrameworkProperties properties;
    private final ControllerRuntimeFactory runtimeFactory;
    private final LeaderElectionAdapter leaderElection;
    private final RuntimeLifecycleSupport support;
    private final ScheduledExecutorService supervisor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("operator-supervisor").factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean leading;
    private volatile boolean electionActive;
    private volatile ControllerRuntime runtime;
    private volatile String lastFailure = "none";
    private CompletableFuture<Void> stoppingRuntime = CompletableFuture.completedFuture(null);

    /**
     * Creates the lifecycle supervisor.
     *
     * @param properties the operator framework configuration
     * @param runtimeFactory the factory creating a controller runtime per term
     * @param leaderElection the leader election adapter
     * @param support the readiness, metrics, and policy-state lifecycle support
     */
    public OperatorFrameworkLifecycle(
            OperatorFrameworkProperties properties,
            ControllerRuntimeFactory runtimeFactory,
            LeaderElectionAdapter leaderElection,
            RuntimeLifecycleSupport support) {
        this.properties = properties;
        this.runtimeFactory = runtimeFactory;
        this.leaderElection = leaderElection;
        this.support = support;
    }

    OperatorFrameworkLifecycle(
            OperatorFrameworkProperties properties,
            ControllerRuntimeFactory runtimeFactory,
            LeaderElectionAdapter leaderElection,
            RuntimeReadiness readiness) {
        this(properties, runtimeFactory, leaderElection, new RuntimeLifecycleSupport(readiness));
    }

    /**
     * Starts supervision asynchronously: leader election when enabled, otherwise the runtime directly.
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        support.start(() -> leading);
        if (properties.getLeaderElection().isEnabled()) {
            support.notReady();
            supervisor.execute(this::startLeaderElection);
        } else {
            supervisor.execute(this::startRuntime);
        }
    }

    /**
     * Stops the runtime and leader election without blocking the caller.
     *
     * @param callback invoked once the stop has finished
     */
    @Override
    public void stop(Runnable callback) {
        if (!running.compareAndSet(true, false)) {
            callback.run();
            return;
        }
        electionActive = false;
        support.notReady();
        supervisor.execute(() -> stopRuntime().whenComplete(
                (ignored, exception) -> finishLifecycleStop(callback)));
    }

    /** Stops the lifecycle without a completion callback. */
    @Override
    public void stop() {
        stop(() -> { });
    }

    /**
     * Reports whether the lifecycle has been started and not yet stopped.
     *
     * @return {@code true} when the lifecycle is running
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Reports whether this instance currently holds leadership.
     *
     * @return {@code true} when this instance is the leader
     */
    public boolean isLeading() {
        return leading;
    }

    /**
     * Reports whether the current runtime's informers have synced.
     *
     * @return {@code true} when a runtime exists and is ready
     */
    public boolean isInformerSynced() {
        var current = runtime;
        return current != null && current.isReady();
    }

    /**
     * Reports whether the current runtime's workers are running.
     *
     * @return {@code true} when a runtime exists and is running
     */
    public boolean isWorkerRunning() {
        var current = runtime;
        return current != null && current.isRunning();
    }

    /**
     * Returns the current runtime's queue depth, or zero when no runtime exists.
     *
     * @return the number of pending reconciliation requests
     */
    public int queueDepth() {
        var current = runtime;
        return current == null ? 0 : current.queueDepth();
    }

    /**
     * Returns the class name of the last recorded failure, or {@code "none"}.
     *
     * @return a description of the last failure
     */
    public String lastFailure() {
        return lastFailure;
    }

    /**
     * Reports that this lifecycle always starts automatically with the context.
     *
     * @return always {@code true}
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void startLeaderElection() {
        try {
            var election = leaderElection.start(this::leadershipStarted, this::leadershipStopped);
            electionActive = true;
            election.whenComplete((ignored, failure) -> electionFinished(failure));
            if (!leading) {
                support.ready();
            }
        } catch (RuntimeException exception) {
            electionActive = false;
            recordFailure(exception);
            log.warn("Leader election startup failed; retrying", exception);
            support.notReady();
            leaderElection.stop();
            schedule(this::startLeaderElection);
        }
    }

    private void electionFinished(Throwable failure) {
        electionActive = false;
        if (!running.get()) {
            return;
        }
        supervisor.execute(() -> {
            recordFailure(failure);
            log.warn("Leader election stopped unexpectedly; retrying", failure);
            leading = false;
            support.notReady();
            stopRuntime().whenComplete((ignored, exception) -> restartLeaderElection());
        });
    }

    private void leadershipStarted() {
        if (running.get()) {
            leading = true;
            support.notReady();
            supervisor.execute(this::startRuntime);
        }
    }

    private void leadershipStopped() {
        leading = false;
        support.notReady();
        if (running.get()) {
            supervisor.execute(() -> stopRuntime().whenComplete(
                    (ignored, exception) -> markStandbyReady()));
        }
    }

    private void markStandbyReady() {
        if (running.get() && !leading && electionActive) {
            support.ready();
        }
    }

    private void startRuntime() {
        if (!shouldRunRuntime() || runtime != null) {
            return;
        }
        if (!stoppingRuntime.isDone()) {
            stoppingRuntime.whenComplete((ignored, exception) -> resumeRuntimeStart());
            return;
        }
        try {
            runtime = runtimeFactory.create();
            runtime.start();
            schedule(this::checkRuntime);
        } catch (RuntimeException exception) {
            recordFailure(exception);
            log.warn("Controller runtime startup failed; retrying", exception);
            support.notReady();
            stopRuntime().whenComplete((ignored, stopFailure) -> schedule(this::startRuntime));
        }
    }

    private void checkRuntime() {
        if (!shouldRunRuntime() || runtime == null) {
            return;
        }
        if (runtime.isReady()) {
            support.ready();
            schedule(this::checkRuntime);
            return;
        }
        support.notReady();
        schedule(this::checkRuntime);
    }

    private CompletableFuture<Void> stopRuntime() {
        var current = runtime;
        runtime = null;
        if (current != null) {
            stoppingRuntime = current.stop().toCompletableFuture()
                    .whenComplete((ignored, exception) -> support.runtimeStopped());
        } else if (stoppingRuntime.isDone()) {
            support.runtimeStopped();
        }
        return stoppingRuntime;
    }

    private void restartLeaderElection() {
        leaderElection.stop();
        schedule(this::startLeaderElection);
    }

    private void finishLifecycleStop(Runnable callback) {
        leaderElection.stop();
        finishStop(callback);
    }

    private void resumeRuntimeStart() {
        if (running.get()) {
            supervisor.execute(this::startRuntime);
        }
    }

    private boolean shouldRunRuntime() {
        return running.get() && (!properties.getLeaderElection().isEnabled() || leading);
    }

    private void recordFailure(Throwable failure) {
        lastFailure = failure == null ? "unexpected completion" : failure.getClass().getName();
    }

    private void schedule(Runnable action) {
        if (running.get()) {
            supervisor.schedule(action, properties.getController().getStartupRetryDelay().toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private void finishStop(Runnable callback) {
        supervisor.shutdownNow();
        support.close();
        callback.run();
    }
}
