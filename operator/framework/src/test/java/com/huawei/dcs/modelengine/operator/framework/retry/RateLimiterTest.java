package com.huawei.dcs.modelengine.operator.framework.retry;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {
    @Test
    void canProcessThrottlesEachResourceKeyIndependently() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiter rateLimiter = new RateLimiter(Duration.ofSeconds(5), clock);

        assertTrue(rateLimiter.canProcess("default/a"));
        rateLimiter.record("default/a");

        assertFalse(rateLimiter.canProcess("default/a"));
        assertTrue(rateLimiter.canProcess("default/b"));

        clock.advance(Duration.ofSeconds(5));

        assertTrue(rateLimiter.canProcess("default/a"));
    }

    @Test
    void zeroIntervalAllowsImmediateProcessing() {
        RateLimiter rateLimiter = new RateLimiter(Duration.ZERO, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        rateLimiter.record("default/a");

        assertTrue(rateLimiter.canProcess("default/a"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
