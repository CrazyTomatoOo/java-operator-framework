package com.example.stress;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically prints windowed throughput/latency and a final summary. Safe to call
 * {@link #printSummary()} more than once (e.g. normal exit plus shutdown hook).
 */
final class Reporter implements AutoCloseable {

    private final StressConfig config;
    private final StressMetrics metrics;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean summaryPrinted = new AtomicBoolean();

    private long startMs;
    private long lastReportMs;
    private long prevWriteOk;
    private long prevWriteErr;
    private long prevReconciles;
    private long prevCoalesced;
    private long prevEchoes;
    private long prevApiReads;
    private long prevApiCreates;
    private long prevApiUpdates;
    private long prevApiDeletes;
    private long prevApiStatusWrites;
    private long prevApiErrors;
    private double peakReconcilesPerSec;
    private double peakWritesPerSec;

    Reporter(StressConfig config, StressMetrics metrics) {
        this.config = config;
        this.metrics = metrics;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "stress-reporter");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        startMs = System.currentTimeMillis();
        lastReportMs = startMs;
        scheduler.scheduleAtFixedRate(this::printWindowSafely,
                config.reportIntervalSec, config.reportIntervalSec, TimeUnit.SECONDS);
    }

    private void printWindowSafely() {
        try {
            printWindow();
        } catch (RuntimeException exception) {
            System.err.println("Report failed: " + exception.getMessage());
        }
    }

    private synchronized void printWindow() {
        long now = System.currentTimeMillis();
        double seconds = (now - lastReportMs) / 1000.0;
        if (seconds <= 0) {
            return;
        }
        long writeOk = metrics.writeOk.sum();
        long writeErr = metrics.writeErr.sum();
        long reconciles = metrics.reconciles.sum();
        long coalesced = metrics.coalesced.sum();
        double writesPerSec = (writeOk - prevWriteOk) / seconds;
        double reconcilesPerSec = (reconciles - prevReconciles) / seconds;
        peakWritesPerSec = Math.max(peakWritesPerSec, writesPerSec);
        peakReconcilesPerSec = Math.max(peakReconcilesPerSec, reconcilesPerSec);
        long lag = writeOk - reconciles;
        long echoes = metrics.echoReconciles.sum();
        long apiReads = metrics.apiReads.sum();
        long apiCreates = metrics.apiCreates.sum();
        long apiUpdates = metrics.apiUpdates.sum();
        long apiDeletes = metrics.apiDeletes.sum();
        long apiStatusWrites = metrics.apiStatusWrites.sum();
        long apiErrors = metrics.apiErrors.sum();
        long[] samples = metrics.latenciesMsSince(lastReportMs);
        System.out.printf("[%6.1fs] writes %8.1f/s (err %d) | reconciles %8.1f/s (echo %6.1f/s) | lag %6d | api r=%5.0f c=%4.0f u=%5.0f d=%4.0f s=%5.0f err=%d | latency ms %s (n=%d) | coalesced +%d%n",
                (now - startMs) / 1000.0,
                writesPerSec,
                writeErr - prevWriteErr,
                reconcilesPerSec,
                (echoes - prevEchoes) / seconds,
                lag,
                (apiReads - prevApiReads) / seconds,
                (apiCreates - prevApiCreates) / seconds,
                (apiUpdates - prevApiUpdates) / seconds,
                (apiDeletes - prevApiDeletes) / seconds,
                (apiStatusWrites - prevApiStatusWrites) / seconds,
                apiErrors - prevApiErrors,
                describe(samples),
                samples.length,
                coalesced - prevCoalesced);
        prevWriteOk = writeOk;
        prevWriteErr = writeErr;
        prevReconciles = reconciles;
        prevCoalesced = coalesced;
        prevEchoes = echoes;
        prevApiReads = apiReads;
        prevApiCreates = apiCreates;
        prevApiUpdates = apiUpdates;
        prevApiDeletes = apiDeletes;
        prevApiStatusWrites = apiStatusWrites;
        prevApiErrors = apiErrors;
        lastReportMs = now;
    }

    /** Idempotent final summary. */
    synchronized void printSummary() {
        if (!summaryPrinted.compareAndSet(false, true)) {
            return;
        }
        double elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0;
        long writeOk = metrics.writeOk.sum();
        long writeErr = metrics.writeErr.sum();
        long reconciles = metrics.reconciles.sum();
        long coalesced = metrics.coalesced.sum();
        long[] samples = metrics.latenciesMsSince(startMs);
        System.out.println();
        System.out.println("==================== STRESS TEST SUMMARY ====================");
        System.out.println("Config:      " + config);
        System.out.printf ("Elapsed:     %.1fs%n", elapsedSec);
        System.out.printf ("Writes:      %d ok, %d err (avg %.1f/s, peak %.1f/s)%n",
                writeOk, writeErr, elapsedSec > 0 ? writeOk / elapsedSec : 0, peakWritesPerSec);
        System.out.printf ("Reconciles:  %d (avg %.1f/s, peak %.1f/s)%n",
                reconciles, elapsedSec > 0 ? reconciles / elapsedSec : 0, peakReconcilesPerSec);
        long echoes = metrics.echoReconciles.sum();
        System.out.printf ("Echoes:      %d no-op reconciles from self-triggered status events%n", echoes);
        long apiWrites = metrics.reconcileApiWrites();
        System.out.printf ("API ops:     reads %d, creates %d, updates %d, deletes %d, status %d, errors %d%n",
                metrics.apiReads.sum(), metrics.apiCreates.sum(), metrics.apiUpdates.sum(),
                metrics.apiDeletes.sum(), metrics.apiStatusWrites.sum(), metrics.apiErrors.sum());
        System.out.printf ("API writes:  %d total (load %d + reconcile %d) = avg %.1f/s%n",
                writeOk + apiWrites, writeOk, apiWrites,
                elapsedSec > 0 ? (writeOk + apiWrites) / elapsedSec : 0);
        System.out.printf ("Coalesced:   %d writes merged before reconcile saw them (%.1f%% of writes)%n",
                coalesced, writeOk > 0 ? coalesced * 100.0 / writeOk : 0);
        System.out.println("End-to-end latency (API write -> reconcile start), n=" + samples.length + ":");
        System.out.println("  " + describe(samples));
        System.out.println("=============================================================");
    }

    private static String describe(long[] samples) {
        if (samples.length == 0) {
            return "p50=- p95=- p99=- max=-";
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return String.format("p50=%.1f p95=%.1f p99=%.1f max=%d",
                percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99), sorted[sorted.length - 1]);
    }

    private static double percentile(long[] sorted, double quantile) {
        int index = (int) Math.ceil(quantile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        printSummary();
    }
}
