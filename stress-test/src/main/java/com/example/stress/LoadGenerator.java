package com.example.stress;

import com.example.stress.api.v1alpha1.StressTestResource;
import com.example.stress.api.v1alpha1.StressTestSpec;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Drives load against the API server. Phase A creates all keys in parallel. Phase B
 * issues paced JSON-merge-patch updates at the configured rate with a hot-key bias:
 * a small set of keys receives most of the traffic while the tail spreads across the
 * remaining keys.
 */
final class LoadGenerator {

    private static final int MAX_CONSECUTIVE_ERRORS = 200;

    private final KubernetesClient client;
    private final StressConfig config;
    private final StressMetrics metrics;
    private final String[] keys;
    private final AtomicLongArray seqs;
    private final String payload;
    private final int hotCount;

    LoadGenerator(KubernetesClient client, StressConfig config, StressMetrics metrics) {
        this.client = client;
        this.config = config;
        this.metrics = metrics;
        this.keys = new String[config.keys];
        for (int i = 0; i < config.keys; i++) {
            this.keys[i] = String.format("stress-%05d", i);
        }
        this.seqs = new AtomicLongArray(config.keys);
        this.payload = "x".repeat(config.payloadSize);
        this.hotCount = Math.min(config.hotKeyCount(), config.keys);
    }

    void createAll() {
        System.out.printf("Phase A: creating %d resources (concurrency=%d)%n", config.keys, config.createConcurrency);
        long startedAt = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(config.createConcurrency);
        AtomicInteger next = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < config.createConcurrency; t++) {
            futures.add(pool.submit(() -> {
                int index;
                while ((index = next.getAndIncrement()) < keys.length) {
                    createOne(keys[index]);
                }
            }));
        }
        pool.shutdown();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException exception) {
                // Individual create failures are already counted in writeErr.
            }
        }
        double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        System.out.printf("Phase A: issued creates in %.1fs (ok=%d err=%d, %.0f creates/s)%n",
                seconds, metrics.writeOk.sum(), metrics.writeErr.sum(),
                seconds > 0 ? metrics.writeOk.sum() / seconds : 0);
    }

    /** Waits until the reconcile count reaches {@code target} or the timeout elapses. */
    boolean awaitReconciles(long target) {
        long deadlineMs = System.currentTimeMillis() + config.drainTimeoutSec * 1000L;
        while (metrics.reconciles.sum() < target && System.currentTimeMillis() < deadlineMs) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return metrics.reconciles.sum() >= target;
    }

    void runSteady() throws InterruptedException {
        System.out.printf("Phase B: steady load — target %d updates/s for %ds (%d writers); hot keys=%d receiving %.0f%% of traffic%n",
                config.rate, config.durationSec, config.writeThreads, hotCount, config.hotTraffic * 100);
        Thread[] writers = new Thread[config.writeThreads];
        for (int t = 0; t < config.writeThreads; t++) {
            writers[t] = new Thread(this::writeLoop, "stress-writer-" + t);
            writers[t].start();
        }
        for (Thread writer : writers) {
            writer.join();
        }
    }

    void deleteAllQuietly() {
        for (String key : keys) {
            try {
                client.resources(StressTestResource.class).inNamespace(config.namespace).withName(key).delete();
            } catch (KubernetesClientException ignored) {
                // Best-effort cleanup.
            }
        }
    }

    private void createOne(String name) {
        StressTestSpec spec = new StressTestSpec();
        spec.seq = 0;
        spec.sentAtMs = System.currentTimeMillis();
        spec.payload = payload;
        StressTestResource resource = new StressTestResource();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace(config.namespace);
        resource.setMetadata(metadata);
        resource.setSpec(spec);
        try {
            client.resources(StressTestResource.class).inNamespace(config.namespace).resource(resource).create();
            metrics.writeOk.increment();
        } catch (KubernetesClientException exception) {
            metrics.writeErr.increment();
        }
    }

    private void writeLoop() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long intervalNanos = 1_000_000_000L * config.writeThreads / config.rate;
        long endAtNanos = System.nanoTime() + config.durationSec * 1_000_000_000L;
        long nextTickNanos = System.nanoTime();
        int consecutiveErrors = 0;
        while (System.nanoTime() < endAtNanos) {
            nextTickNanos += intervalNanos;
            if (doOneUpdate(random)) {
                consecutiveErrors = 0;
            } else if (++consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                System.err.println("Writer aborting after " + consecutiveErrors + " consecutive errors");
                return;
            }
            long sleepNanos = nextTickNanos - System.nanoTime();
            if (sleepNanos > 0) {
                LockSupport.parkNanos(sleepNanos);
            } else if (sleepNanos < -intervalNanos * 50) {
                // Fell far behind (e.g. API server slower than target rate); reset pacing.
                nextTickNanos = System.nanoTime();
            }
        }
    }

    private boolean doOneUpdate(ThreadLocalRandom random) {
        int index = pickIndex(random);
        long seq = seqs.incrementAndGet(index);
        String patch = "{\"spec\":{\"seq\":" + seq + ",\"sentAtMs\":" + System.currentTimeMillis() + "}}";
        try {
            client.resources(StressTestResource.class).inNamespace(config.namespace)
                    .withName(keys[index])
                    .patch(PatchContext.of(PatchType.JSON_MERGE), patch);
            metrics.writeOk.increment();
            return true;
        } catch (KubernetesClientException exception) {
            metrics.writeErr.increment();
            return false;
        }
    }

    private int pickIndex(ThreadLocalRandom random) {
        if (random.nextDouble() < config.hotTraffic) {
            return random.nextInt(hotCount);
        }
        return random.nextInt(keys.length);
    }
}
