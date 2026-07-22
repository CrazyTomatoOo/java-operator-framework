package com.huawei.dcs.modelengine.operator.framework.retry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Per-resource rate limiter backed by last-processing timestamps. */
public final class RateLimiter {
    public static final Duration DEFAULT_MINIMUM_INTERVAL = Duration.ofSeconds(5);

    private final Duration minimumInterval;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> lastProcessedAt = new ConcurrentHashMap<>();

    public RateLimiter() {
        this(DEFAULT_MINIMUM_INTERVAL);
    }

    public RateLimiter(Duration minimumInterval) {
        this(minimumInterval, Clock.systemUTC());
    }

    public RateLimiter(Duration minimumInterval, Clock clock) {
        Objects.requireNonNull(minimumInterval, "minimumInterval must not be null");
        if (minimumInterval.isNegative()) {
            throw new IllegalArgumentException("minimumInterval must not be negative");
        }
        this.minimumInterval = minimumInterval;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public boolean canProcess(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Instant lastProcessed = lastProcessedAt.get(key);
        return lastProcessed == null || !clock.instant().isBefore(lastProcessed.plus(minimumInterval()));
    }

    public void record(String key) {
        Objects.requireNonNull(key, "key must not be null");
        lastProcessedAt.put(key, clock.instant());
    }

    public Duration minimumInterval() {
        return minimumInterval;
    }
}
