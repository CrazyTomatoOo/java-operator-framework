package com.huawei.dcs.modelengine.operator.framework.leader;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElector;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.ConfigMapLock;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.Lock;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fabric8 leader election wrapper for running operator work only on the current leader.
 */
public final class LeaderElectionManager {
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(15);
    public static final Duration DEFAULT_RENEW_DEADLINE = Duration.ofSeconds(10);
    public static final Duration DEFAULT_RETRY_PERIOD = Duration.ofSeconds(2);

    private final KubernetesClient client;
    private final String lockName;
    private final String namespace;
    private final String identity;
    private Duration leaseDuration = DEFAULT_LEASE_DURATION;
    private Duration renewDeadline = DEFAULT_RENEW_DEADLINE;
    private Duration retryPeriod = DEFAULT_RETRY_PERIOD;
    private LockMode lockMode = LockMode.LEASE;
    private Listener listener = Listener.noop();
    private volatile Thread leaderThread;

    public LeaderElectionManager(KubernetesClient client, String lockName, String namespace) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.lockName = requireText(lockName, "lockName");
        this.namespace = requireText(namespace, "namespace");
        this.identity = createIdentity();
    }

    public LeaderElectionManager withLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        return this;
    }

    public LeaderElectionManager withRenewDeadline(Duration renewDeadline) {
        this.renewDeadline = requirePositive(renewDeadline, "renewDeadline");
        return this;
    }

    public LeaderElectionManager withRetryPeriod(Duration retryPeriod) {
        this.retryPeriod = requirePositive(retryPeriod, "retryPeriod");
        return this;
    }

    public LeaderElectionManager withLockMode(LockMode lockMode) {
        this.lockMode = Objects.requireNonNull(lockMode, "lockMode must not be null");
        return this;
    }

    public LeaderElectionManager withListener(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        return this;
    }

    public void run(Runnable leaderRunnable) {
        Objects.requireNonNull(leaderRunnable, "leaderRunnable must not be null");
        LeaderElector leaderElector = client.leaderElector()
                .withConfig(config(leaderRunnable))
                .build();
        CompletableFuture<?> election = leaderElector.start();
        election.join();
    }

    String identity() {
        return identity;
    }

    private LeaderElectionConfig config(Runnable leaderRunnable) {
        return new LeaderElectionConfigBuilder()
                .withReleaseOnCancel()
                .withName(lockName)
                .withLeaseDuration(leaseDuration)
                .withLock(lock())
                .withRenewDeadline(renewDeadline)
                .withRetryPeriod(retryPeriod)
                .withLeaderCallbacks(new LeaderCallbacks(
                        () -> startLeading(leaderRunnable),
                        this::stopLeading,
                        listener::onNewLeader))
                .build();
    }

    private Lock lock() {
        return switch (lockMode) {
            case LEASE -> new LeaseLock(namespace, lockName, identity);
            case CONFIG_MAP -> new ConfigMapLock(namespace, lockName, identity);
        };
    }

    private void startLeading(Runnable leaderRunnable) {
        listener.onStartLeading();
        Thread thread = new Thread(leaderRunnable, "leader-election-" + lockName);
        leaderThread = thread;
        thread.start();
    }

    private void stopLeading() {
        listener.onStopLeading();
        Thread thread = leaderThread;
        leaderThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static String createIdentity() {
        String uuid = UUID.randomUUID().toString();
        return Optional.ofNullable(System.getenv("HOSTNAME"))
                .filter(hostname -> !hostname.isBlank())
                .map(hostname -> uuid + ":" + hostname)
                .orElse(uuid);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    public enum LockMode {
        LEASE,
        CONFIG_MAP
    }

    public interface Listener {
        void onStartLeading();

        void onStopLeading();

        default void onNewLeader(String identity) {
        }

        static Listener noop() {
            return new Listener() {
                @Override
                public void onStartLeading() {
                }

                @Override
                public void onStopLeading() {
                }
            };
        }
    }
}
