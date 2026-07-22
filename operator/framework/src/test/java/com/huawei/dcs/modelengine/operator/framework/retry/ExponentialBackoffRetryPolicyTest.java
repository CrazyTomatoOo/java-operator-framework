package com.huawei.dcs.modelengine.operator.framework.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExponentialBackoffRetryPolicyTest {
    @Test
    void nextDelayDoublesUntilMaxInterval() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(Duration.ofMillis(500),
                Duration.ofSeconds(2), 5);

        assertEquals(Duration.ofMillis(500), policy.nextDelay(0));
        assertEquals(Duration.ofSeconds(1), policy.nextDelay(1));
        assertEquals(Duration.ofSeconds(2), policy.nextDelay(2));
        assertEquals(Duration.ofSeconds(2), policy.nextDelay(3));
        assertEquals(Duration.ofSeconds(2), policy.nextDelay(4));
    }

    @Test
    void nextDelayRejectsAttemptsOutsideConfiguredRange() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(Duration.ofMillis(100),
                Duration.ofSeconds(1), 2);

        assertThrows(IllegalArgumentException.class, () -> policy.nextDelay(-1));
        assertThrows(IllegalArgumentException.class, () -> policy.nextDelay(2));
    }

    @Test
    void defaultPolicyUsesExpectedConfiguration() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy();

        assertEquals(Duration.ofMillis(500), policy.nextDelay(0));
        assertEquals(Duration.ofSeconds(8), policy.nextDelay(4));
        assertThrows(IllegalArgumentException.class, () -> policy.nextDelay(5));
    }
}
