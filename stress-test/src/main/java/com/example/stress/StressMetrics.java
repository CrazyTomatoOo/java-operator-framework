package com.example.stress;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe metrics sink: LongAdder counters plus a striped ring-buffer of
 * latency samples. Each sample is (completion time, latency in ms). Rings keep the
 * most recent STRIPES * CAPACITY samples; percentiles are computed over arbitrary
 * time windows by filtering on completion time.
 */
final class StressMetrics {

    private static final int STRIPES = 16;
    private static final int CAPACITY = 16384;

    final LongAdder writeOk = new LongAdder();
    final LongAdder writeErr = new LongAdder();
    final LongAdder reconciles = new LongAdder();
    final LongAdder coalesced = new LongAdder();
    final LongAdder echoReconciles = new LongAdder();
    final LongAdder apiReads = new LongAdder();
    final LongAdder apiCreates = new LongAdder();
    final LongAdder apiUpdates = new LongAdder();
    final LongAdder apiDeletes = new LongAdder();
    final LongAdder apiStatusWrites = new LongAdder();
    final LongAdder apiErrors = new LongAdder();

    long reconcileApiWrites() {
        return apiCreates.sum() + apiUpdates.sum() + apiDeletes.sum() + apiStatusWrites.sum();
    }

    private final long[][] latMs = new long[STRIPES][CAPACITY];
    private final long[][] atMs = new long[STRIPES][CAPACITY];
    private final int[] cursor = new int[STRIPES];

    void recordLatency(long completedAtMs, long latencyMs) {
        int stripe = (int) (Thread.currentThread().threadId() & (STRIPES - 1));
        synchronized (latMs[stripe]) {
            int index = cursor[stripe]++ & (CAPACITY - 1);
            latMs[stripe][index] = latencyMs;
            atMs[stripe][index] = completedAtMs;
        }
    }

    /** Copies latency samples (ms) whose completion time is at or after {@code sinceMs}. */
    long[] latenciesMsSince(long sinceMs) {
        long[][] perStripe = new long[STRIPES][];
        int total = 0;
        for (int stripe = 0; stripe < STRIPES; stripe++) {
            synchronized (latMs[stripe]) {
                int size = Math.min(cursor[stripe], CAPACITY);
                long[] matches = new long[size];
                int count = 0;
                for (int i = 0; i < size; i++) {
                    if (atMs[stripe][i] >= sinceMs) {
                        matches[count++] = latMs[stripe][i];
                    }
                }
                perStripe[stripe] = Arrays.copyOf(matches, count);
                total += count;
            }
        }
        long[] all = new long[total];
        int position = 0;
        for (long[] part : perStripe) {
            System.arraycopy(part, 0, all, position, part.length);
            position += part.length;
        }
        return all;
    }
}
