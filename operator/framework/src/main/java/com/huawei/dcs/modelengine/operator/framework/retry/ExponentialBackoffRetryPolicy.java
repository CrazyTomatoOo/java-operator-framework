package com.huawei.dcs.modelengine.operator.framework.retry;

import java.time.Duration;
import java.util.Objects;

/** Retry policy with bounded exponential backoff. */
public final class ExponentialBackoffRetryPolicy implements RetryPolicy {
    public static final Duration DEFAULT_INITIAL_INTERVAL = Duration.ofMillis(500);
    public static final Duration DEFAULT_MAX_INTERVAL = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final Duration initialInterval;
    private final Duration maxInterval;
    private final int maxAttempts;

    public ExponentialBackoffRetryPolicy() {
        this(DEFAULT_INITIAL_INTERVAL, DEFAULT_MAX_INTERVAL, DEFAULT_MAX_ATTEMPTS);
    }

    public ExponentialBackoffRetryPolicy(Duration initialInterval, Duration maxInterval, int maxAttempts) {
        this.initialInterval = requirePositive(initialInterval, "initialInterval");
        this.maxInterval = requirePositive(maxInterval, "maxInterval");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
    }

    @Override
    public Duration nextDelay(int attempt) {
        if (attempt < 0 || attempt >= maxAttempts) {
            throw new IllegalArgumentException("attempt must be between 0 and maxAttempts - 1");
        }
        long multiplier = 1L << Math.min(attempt, 62);
        Duration delay;
        try {
            delay = initialInterval.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            delay = maxInterval;
        }
        return delay.compareTo(maxInterval) > 0 ? maxInterval : delay;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
