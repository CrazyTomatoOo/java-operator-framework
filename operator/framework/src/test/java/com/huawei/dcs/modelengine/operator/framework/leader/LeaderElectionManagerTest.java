package com.huawei.dcs.modelengine.operator.framework.leader;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElector;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectorBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.ConfigMapLock;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaderElectionManagerTest {

    @Test
    void runBuildsLeaderElectionConfigWithDefaultDurations() {
        KubernetesClient client = mock(KubernetesClient.class);
        LeaderElectorBuilder builder = mock(LeaderElectorBuilder.class);
        LeaderElector leaderElector = mock(LeaderElector.class);
        when(client.leaderElector()).thenReturn(builder);
        when(builder.withConfig(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
        when(builder.build()).thenReturn(leaderElector);
        when(leaderElector.start()).thenReturn(CompletableFuture.completedFuture(null));

        LeaderElectionManager manager = new LeaderElectionManager(client, "operator-lock", "operator-system");
        manager.run(() -> { });

        ArgumentCaptor<LeaderElectionConfig> configCaptor = ArgumentCaptor.forClass(LeaderElectionConfig.class);
        verify(builder).withConfig(configCaptor.capture());
        LeaderElectionConfig config = configCaptor.getValue();
        assertEquals(LeaderElectionManager.DEFAULT_LEASE_DURATION, config.getLeaseDuration());
        assertEquals(LeaderElectionManager.DEFAULT_RENEW_DEADLINE, config.getRenewDeadline());
        assertEquals(LeaderElectionManager.DEFAULT_RETRY_PERIOD, config.getRetryPeriod());
        assertEquals("operator-lock", config.getName());
        assertTrue(config.isReleaseOnCancel());
        assertInstanceOf(LeaseLock.class, config.getLock());
        assertEquals(manager.identity(), config.getLock().identity());
    }

    @Test
    void runBuildsLeaderElectionConfigWithConfigMapLock() {
        KubernetesClient client = mock(KubernetesClient.class);
        LeaderElectorBuilder builder = mock(LeaderElectorBuilder.class);
        LeaderElector leaderElector = mock(LeaderElector.class);
        when(client.leaderElector()).thenReturn(builder);
        when(builder.withConfig(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
        when(builder.build()).thenReturn(leaderElector);
        when(leaderElector.start()).thenReturn(CompletableFuture.completedFuture(null));

        LeaderElectionManager manager = new LeaderElectionManager(client, "operator-lock", "operator-system")
                .withLockMode(LeaderElectionManager.LockMode.CONFIG_MAP);
        manager.run(() -> { });

        ArgumentCaptor<LeaderElectionConfig> configCaptor = ArgumentCaptor.forClass(LeaderElectionConfig.class);
        verify(builder).withConfig(configCaptor.capture());
        LeaderElectionConfig config = configCaptor.getValue();
        assertInstanceOf(ConfigMapLock.class, config.getLock());
        assertEquals(manager.identity(), config.getLock().identity());
    }

    @Test
    void runUsesConfiguredDurationsAndCallbacksDoNotExitJvm() throws Exception {
        KubernetesClient client = mock(KubernetesClient.class);
        LeaderElectorBuilder builder = mock(LeaderElectorBuilder.class);
        LeaderElector leaderElector = mock(LeaderElector.class);
        when(client.leaderElector()).thenReturn(builder);
        when(builder.withConfig(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
        when(builder.build()).thenReturn(leaderElector);
        when(leaderElector.start()).thenReturn(CompletableFuture.completedFuture(null));
        RecordingListener listener = new RecordingListener();
        CountDownLatch runnableStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Runnable leaderRunnable = () -> {
            runnableStarted.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        };

        LeaderElectionManager manager = new LeaderElectionManager(client, "operator-lock", "operator-system")
                .withLeaseDuration(Duration.ofSeconds(30))
                .withRenewDeadline(Duration.ofSeconds(20))
                .withRetryPeriod(Duration.ofSeconds(5))
                .withListener(listener);
        manager.run(leaderRunnable);

        ArgumentCaptor<LeaderElectionConfig> configCaptor = ArgumentCaptor.forClass(LeaderElectionConfig.class);
        verify(builder).withConfig(configCaptor.capture());
        LeaderElectionConfig config = configCaptor.getValue();
        assertEquals(Duration.ofSeconds(30), config.getLeaseDuration());
        assertEquals(Duration.ofSeconds(20), config.getRenewDeadline());
        assertEquals(Duration.ofSeconds(5), config.getRetryPeriod());

        LeaderCallbacks callbacks = config.getLeaderCallbacks();
        assertDoesNotThrow(callbacks::onStartLeading);
        assertTrue(runnableStarted.await(2, TimeUnit.SECONDS));
        assertTrue(listener.started);

        assertDoesNotThrow(callbacks::onStopLeading);
        waitUntilInterrupted(interrupted);
        assertTrue(listener.stopped);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    private static void waitUntilInterrupted(AtomicBoolean interrupted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!interrupted.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(interrupted.get());
    }

    private static final class RecordingListener implements LeaderElectionManager.Listener {
        private boolean started;
        private boolean stopped;

        @Override
        public void onStartLeading() {
            started = true;
        }

        @Override
        public void onStopLeading() {
            stopped = true;
        }
    }
}
