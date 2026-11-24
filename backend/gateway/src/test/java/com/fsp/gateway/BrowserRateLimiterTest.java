package com.fsp.gateway;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression: per-client browser rate limit at the gateway edge. */
class BrowserRateLimiterTest {

    /** Clock whose instant the test can advance manually. */
    static class TickingClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private static BrowserRateLimiter limiter(TickingClock clock, int maxRequests, Duration window) {
        GatewayProperties props = new GatewayProperties();
        props.setRateLimit(new GatewayProperties.RateLimit(true, maxRequests, window));
        return new BrowserRateLimiter(clock, props);
    }

    @Test
    void requestsUnderLimitAreAllowed() {
        TickingClock clock = new TickingClock();
        BrowserRateLimiter t = limiter(clock, 2, Duration.ofSeconds(1));
        assertThat(t.check("client-a").allowed()).isTrue();
        assertThat(t.check("client-a").allowed()).isTrue();
    }

    @Test
    void requestOverLimitIsRejectedWithRetryAfter() {
        TickingClock clock = new TickingClock();
        BrowserRateLimiter t = limiter(clock, 2, Duration.ofSeconds(1));
        t.check("client-a");
        t.check("client-a");
        BrowserRateLimiter.Verdict v = t.check("client-a");
        assertThat(v.allowed()).isFalse();
        assertThat(v.retryAfter()).isNotNull();
        assertThat(v.retryAfter().isNegative() || v.retryAfter().isZero()).isFalse();
    }

    @Test
    void differentClientsAreIsolated() {
        TickingClock clock = new TickingClock();
        BrowserRateLimiter t = limiter(clock, 1, Duration.ofSeconds(1));
        t.check("client-a");
        assertThat(t.check("client-b").allowed()).isTrue(); // other client not affected
    }

    @Test
    void expiredWindowAllowsAgain() {
        TickingClock clock = new TickingClock();
        BrowserRateLimiter t = limiter(clock, 1, Duration.ofSeconds(1));
        assertThat(t.check("client-a").allowed()).isTrue(); // t = T0
        assertThat(t.check("client-a").allowed()).isFalse(); // T0, within window
        clock.advance(Duration.ofSeconds(2)); // T0 + 2s, outside the 1s window
        assertThat(t.check("client-a").allowed()).isTrue(); // window expired, allowed again
    }

    @Test
    void disabledRateLimitAllowsEverything() {
        GatewayProperties props = new GatewayProperties();
        props.setRateLimit(new GatewayProperties.RateLimit(false, 1, Duration.ofSeconds(1)));
        BrowserRateLimiter t = new BrowserRateLimiter(new TickingClock(), props);
        for (int i = 0; i < 100; i++) {
            assertThat(t.check("client-a").allowed()).isTrue();
        }
    }
}
