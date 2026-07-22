package com.example.stress.api.v1alpha1;

/**
 * Spec of the synthetic resource used by the stress test. The load generator bumps
 * {@code seq} and stamps {@code sentAtMs} (wall-clock millis at write time) on every
 * update so the reconciler can measure end-to-end event latency.
 */
public class StressTestSpec {

    public long seq;

    public long sentAtMs;

    public String payload;

    public StressTestSpec() {
    }
}
