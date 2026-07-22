package com.example.stress.api.v1alpha1;

/** Status written back by the reconciler on every processed event (crud mode). */
public class StressTestStatus {

    public long observedSeq;

    public String phase;

    public long lastReconcileMs;

    public StressTestStatus() {
    }
}
