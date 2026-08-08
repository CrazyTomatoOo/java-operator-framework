/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.RuntimeReadiness;
import com.huawei.dcs.modelengine.operator.framework.internal.leader.LeaderElectionAdapter;
import com.huawei.dcs.modelengine.operator.framework.internal.policy.ReconcileRateLimitAspect;
import com.huawei.dcs.modelengine.operator.framework.internal.policy.ReconcileRetryAspect;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class OperatorFrameworkLifecycleTest {
    @Test
    void startIsNonBlockingAndRetriesTransientStartupFailure() throws Exception {
        var properties = properties();
        var readyRuntime = new TestRuntime(true);
        var factory = new TestRuntimeFactory(new FailingRuntime(), readyRuntime);
        var readiness = readiness();
        var lifecycle = new OperatorFrameworkLifecycle(properties, factory, new TestLeaderElection(), readiness);

        var startedAt = System.nanoTime();
        lifecycle.start();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(100));
        assertThat(lifecycle.isRunning()).isTrue();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(factory.creations).hasValue(2);
            assertThat(readiness.isReady()).isTrue();
        });
        readyRuntime.stopped.complete(null);
        stop(lifecycle);
    }

    private RuntimeReadiness readiness() {
        var readiness = new RuntimeReadiness(event -> {}, false);
        readiness.onApplicationEvent(null);
        return readiness;
    }

    private OperatorFrameworkProperties properties() {
        var properties = new OperatorFrameworkProperties();
        properties.getController().setStartupRetryDelay(Duration.ofMillis(10));
        return properties;
    }

    private void stop(OperatorFrameworkLifecycle lifecycle) throws InterruptedException {
        var latch = new CountDownLatch(1);
        lifecycle.stop(latch::countDown);
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void unsyncedRuntimeStaysAliveWhileReadinessIsRetried() throws Exception {
        var runtime = new TestRuntime(false);
        var factory = new TestRuntimeFactory(runtime);
        var readiness = readiness();
        var lifecycle = new OperatorFrameworkLifecycle(properties(), factory, new TestLeaderElection(), readiness);

        lifecycle.start();
        await().during(Duration.ofMillis(50))
            .atMost(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(factory.creations).hasValue(1));
        assertThat(readiness.isReady()).isFalse();

        runtime.stopped.complete(null);
        stop(lifecycle);
    }

    @Test
    void shutdownCallbackWaitsForRuntimeDrain() throws Exception {
        var runtime = new TestRuntime(true);
        var lifecycle =
            new OperatorFrameworkLifecycle(properties(), new TestRuntimeFactory(runtime), new TestLeaderElection(),
                readiness());
        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(runtime.started).isTrue());

        var stopped = new CountDownLatch(1);
        lifecycle.stop(stopped::countDown);
        assertThat(stopped.await(50, TimeUnit.MILLISECONDS)).isFalse();
        runtime.stopped.complete(null);
        assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.stopCalls).hasValue(1);
    }

    @Test
    void standbyIsReadyAndEachLeadershipTermGetsFreshRuntime() throws Exception {
        var properties = properties();
        properties.getLeaderElection().setEnabled(true);
        var leader = new TestLeaderElection();
        var first = new TestRuntime(true);
        var second = new TestRuntime(true);
        var factory = new TestRuntimeFactory(first, second);
        var readiness = readiness();
        var lifecycle = new OperatorFrameworkLifecycle(properties, factory, leader, readiness);

        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThat(leader.started).isTrue();
            assertThat(readiness.isReady()).isTrue();
        });

        leader.startLeading.run();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(factory.creations).hasValue(1));
        leader.stopLeading.run();
        first.stopped.complete(null);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(first.stopCalls).hasValue(1));
        assertThat(readiness.isReady()).isTrue();

        leader.startLeading.run();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(factory.creations).hasValue(2));
        second.stopped.complete(null);
        stop(lifecycle);
    }

    @Test
    void retriesWhenLeaderElectionLoopFailsAsynchronously() throws Exception {
        var properties = properties();
        properties.getLeaderElection().setEnabled(true);
        var leader = new TestLeaderElection();
        var lifecycle = new OperatorFrameworkLifecycle(properties, new TestRuntimeFactory(), leader, readiness());

        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(leader.startCalls).hasValue(1));
        leader.fail(new IllegalStateException("lease watch failed"));
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(leader.startCalls).hasValue(2));

        stop(lifecycle);
    }

    @Test
    void stopsActiveRuntimeBeforeRetryingFailedElection() throws Exception {
        var properties = properties();
        properties.getLeaderElection().setEnabled(true);
        var leader = new TestLeaderElection();
        var runtime = new TestRuntime(true);
        var lifecycle =
            new OperatorFrameworkLifecycle(properties, new TestRuntimeFactory(runtime), leader, readiness());

        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(leader.startCalls).hasValue(1));
        leader.startLeading.run();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(runtime.started).isTrue());
        leader.fail(new IllegalStateException("lease watch failed"));
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(runtime.stopCalls).hasValue(1));
        await().during(Duration.ofMillis(50))
            .atMost(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(leader.startCalls).hasValue(1));

        runtime.stopped.complete(null);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(leader.startCalls).hasValue(2));
        stop(lifecycle);
    }

    @Test
    void policyStateClearsBetweenLeadershipTerms() throws Throwable {
        var properties = properties();
        properties.getLeaderElection().setEnabled(true);
        var retry =
            new ReconcileRetryAspect(properties, new OperatorFrameworkMetrics(null), new DefaultListableBeanFactory(),
                () -> 1.0);
        var rateLimit =
            new ReconcileRateLimitAspect(properties, java.time.Clock.systemUTC(), new DefaultListableBeanFactory());
        var readiness = readiness();
        var support = new RuntimeLifecycleSupport(readiness, new OperatorFrameworkMetrics(null), retry, rateLimit);
        var leader = new TestLeaderElection();
        var first = new TestRuntime(true);
        var second = new TestRuntime(true);
        var factory = new TestRuntimeFactory(first, second);
        var lifecycle = new OperatorFrameworkLifecycle(properties, factory, leader, support);
        var retryPoint = policyJoinPoint();
        var ratePoint = policyJoinPoint();
        when(retryPoint.proceed()).thenThrow(new IllegalStateException("temporary"));
        when(ratePoint.proceed()).thenReturn(ReconcileResult.done());

        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(leader.started).isTrue());
        leader.startLeading.run();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(factory.creations).hasValue(1));
        assertThat(((ReconcileResult) retry.retry(retryPoint)).requeueDelay()).contains(Duration.ofMillis(500));
        assertThat(((ReconcileResult) rateLimit.limit(ratePoint)).isDone()).isTrue();
        assertThat(((ReconcileResult) rateLimit.limit(ratePoint)).requeueDelay()).isPresent();

        leader.stopLeading.run();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(first.stopCalls).hasValue(1));
        assertThat(((ReconcileResult) retry.retry(retryPoint)).requeueDelay()).contains(Duration.ofSeconds(1));
        assertThat(((ReconcileResult) rateLimit.limit(ratePoint)).requeueDelay()).isPresent();
        first.stopped.complete(null);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(first.stopCalls).hasValue(1));
        leader.startLeading.run();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(factory.creations).hasValue(2));

        assertThat(((ReconcileResult) retry.retry(retryPoint)).requeueDelay()).contains(Duration.ofMillis(500));
        assertThat(((ReconcileResult) rateLimit.limit(ratePoint)).isDone()).isTrue();
        verify(ratePoint, times(2)).proceed();
        second.stopped.complete(null);
        stop(lifecycle);
    }

    private ProceedingJoinPoint policyJoinPoint() {
        var point = mock(ProceedingJoinPoint.class);
        var resource = new ConfigMapBuilder().withNewMetadata()
            .withNamespace("operators")
            .withName("sample")
            .withUid("uid")
            .endMetadata()
            .build();
        var context = ReconciliationContext.withoutCache(new ResourceKey("operators", "sample"), List.of());
        when(point.getArgs()).thenReturn(new Object[]{resource, context});
        when(point.getTarget()).thenReturn(new Object());
        return point;
    }

    @Test
    void startStopAreIdempotentAndAccessorsReflectRuntimeState() throws Exception {
        var runtime = new TestRuntime(true);
        var factory = new TestRuntimeFactory(runtime);
        var lifecycle = new OperatorFrameworkLifecycle(properties(), factory, new TestLeaderElection(), readiness());

        assertThat(lifecycle.lastFailure()).isEqualTo("none");
        assertThat(lifecycle.isLeading()).isFalse();
        assertThat(lifecycle.isInformerSynced()).isFalse();
        assertThat(lifecycle.isWorkerRunning()).isFalse();
        assertThat(lifecycle.queueDepth()).isZero();

        lifecycle.start();
        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(factory.creations).hasValue(1));
        assertThat(lifecycle.isInformerSynced()).isTrue();
        assertThat(lifecycle.isWorkerRunning()).isTrue();

        var stopped = new CountDownLatch(1);
        lifecycle.stop(stopped::countDown);
        runtime.stopped.complete(null);
        assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();

        var repeated = new CountDownLatch(1);
        lifecycle.stop(repeated::countDown);
        assertThat(repeated.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(lifecycle.isInformerSynced()).isFalse();
        lifecycle.stop();
    }

    @Test
    void retriesWhenLeaderElectionStartThrowsSynchronously() throws Exception {
        var properties = properties();
        properties.getLeaderElection().setEnabled(true);
        var leader = new TestLeaderElection();
        leader.failStartWith(new IllegalStateException("no lease endpoint"));
        var lifecycle = new OperatorFrameworkLifecycle(properties, new TestRuntimeFactory(), leader, readiness());

        lifecycle.start();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(leader.startCalls).hasValue(2));
        assertThat(lifecycle.lastFailure()).isEqualTo(IllegalStateException.class.getName());

        stop(lifecycle);
    }

    @Test
    void completedElectionRestartsAndRecordsUnexpectedCompletion() throws Exception {
        var properties = properties();
        properties.getLeaderElection().setEnabled(true);
        var leader = new TestLeaderElection();
        var lifecycle = new OperatorFrameworkLifecycle(properties, new TestRuntimeFactory(), leader, readiness());

        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(leader.startCalls).hasValue(1));
        leader.completeElection();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(leader.startCalls).hasValue(2));
        assertThat(lifecycle.lastFailure()).isEqualTo("unexpected completion");

        stop(lifecycle);
    }

    @Test
    void stopWithoutLeadershipTermCompletesCallback() throws Exception {
        var properties = properties();
        properties.getLeaderElection().setEnabled(true);
        var leader = new TestLeaderElection();
        var lifecycle = new OperatorFrameworkLifecycle(properties, new TestRuntimeFactory(), leader, readiness());

        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(leader.startCalls).hasValue(1));

        stop(lifecycle);
    }

    @Test
    void leadershipRegainedDuringDrainResumesRuntimeAfterStopCompletes() throws Exception {
        var properties = properties();
        properties.getLeaderElection().setEnabled(true);
        var leader = new TestLeaderElection();
        var first = new TestRuntime(true);
        var second = new TestRuntime(true);
        var factory = new TestRuntimeFactory(first, second);
        var lifecycle = new OperatorFrameworkLifecycle(properties, factory, leader, readiness());

        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(leader.started).isTrue());
        leader.startLeading.run();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(first.started).isTrue());

        leader.stopLeading.run();
        leader.startLeading.run();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(first.stopCalls).hasValue(1));
        assertThat(factory.creations).hasValue(1);

        first.stopped.complete(null);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(factory.creations).hasValue(2));

        second.stopped.complete(null);
        stop(lifecycle);
    }

    @Test
    void repeatedLeadershipStartKeepsExistingRuntime() throws Exception {
        var properties = properties();
        properties.getLeaderElection().setEnabled(true);
        var leader = new TestLeaderElection();
        var runtime = new TestRuntime(true);
        var factory = new TestRuntimeFactory(runtime);
        var lifecycle = new OperatorFrameworkLifecycle(properties, factory, leader, readiness());

        lifecycle.start();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(leader.started).isTrue());
        leader.startLeading.run();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(factory.creations).hasValue(1));

        leader.startLeading.run();
        await().during(Duration.ofMillis(50))
            .atMost(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(factory.creations).hasValue(1));

        runtime.stopped.complete(null);
        stop(lifecycle);
    }

    private static final class TestRuntimeFactory implements ControllerRuntimeFactory {
        private final Queue<ControllerRuntime> runtimes = new ArrayDeque<>();

        private final AtomicInteger creations = new AtomicInteger();

        private TestRuntimeFactory(ControllerRuntime... runtimes) {
            this.runtimes.addAll(java.util.List.of(runtimes));
        }

        /**
         * Creates the next queued runtime and counts the creation.
         *
         * @return the next queued runtime
         */
        @Override
        public ControllerRuntime create() {
            creations.incrementAndGet();
            return runtimes.remove();
        }
    }

    private static class TestRuntime implements ControllerRuntime {
        private final boolean ready;

        private final CompletableFuture<Void> stopped = new CompletableFuture<>();

        private final AtomicInteger stopCalls = new AtomicInteger();

        private volatile boolean started;

        private TestRuntime(boolean ready) {
            this.ready = ready;
        }

        /**
         * Marks the runtime as started.
         */
        @Override
        public void start() {
            started = true;
        }

        /**
         * Reports the configured readiness.
         *
         * @return the configured readiness flag
         */
        @Override
        public boolean isReady() {
            return ready;
        }

        /**
         * Counts the stop call and returns the controllable stop future.
         *
         * @return the stop future, completed by the test
         */
        @Override
        public java.util.concurrent.CompletionStage<Void> stop() {
            stopCalls.incrementAndGet();
            return stopped;
        }
    }

    private static final class FailingRuntime implements ControllerRuntime {
        /**
         * Always fails to start, simulating a temporary Kubernetes failure.
         *
         * @throws IllegalStateException always
         */
        @Override
        public void start() {
            throw new IllegalStateException("temporary Kubernetes failure");
        }

        /**
         * Reports the runtime as never ready.
         *
         * @return always false
         */
        @Override
        public boolean isReady() {
            return false;
        }

        /**
         * Stops immediately.
         *
         * @return an already-completed stage
         */
        @Override
        public java.util.concurrent.CompletionStage<Void> stop() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class TestLeaderElection implements LeaderElectionAdapter {
        private final AtomicInteger startCalls = new AtomicInteger();

        private Runnable startLeading;

        private Runnable stopLeading;

        private volatile boolean started;

        private volatile CompletableFuture<Void> election;

        private volatile RuntimeException startFailure;

        /**
         * Records the leadership callbacks and returns a controllable election future.
         *
         * @param onStartLeading callback invoked when leadership is acquired
         * @param onStopLeading callback invoked when leadership is lost
         * @return the election future, completed or failed by the test
         */
        @Override
        public java.util.concurrent.CompletionStage<Void> start(Runnable onStartLeading, Runnable onStopLeading) {
            var failure = startFailure;
            startFailure = null;
            startCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            startLeading = onStartLeading;
            stopLeading = onStopLeading;
            started = true;
            election = new CompletableFuture<>();
            return election;
        }

        /**
         * Cancels the running election, if any.
         */
        @Override
        public void stop() {
            if (election != null) {
                election.cancel(true);
            }
        }

        void fail(Throwable failure) {
            election.completeExceptionally(failure);
        }

        void failStartWith(RuntimeException failure) {
            startFailure = failure;
        }

        void completeElection() {
            election.complete(null);
        }
    }
}
