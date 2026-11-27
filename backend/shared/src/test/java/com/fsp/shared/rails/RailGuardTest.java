package com.fsp.shared.rails;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage-1 pins: the response taxonomy semantics (plan §3.3) and the guard discipline
 * (plan §8.2–8.4): breakers on comm health, never on scheme decisions; 429 freezes the
 * window and never trips the breaker; bulkheads refuse rather than queue.
 */
class RailGuardTest {

    private static RailResult ok() {
        return new RailResult.Accepted("rail-1", Instant.now().plus(Duration.ofDays(1)));
    }

    @Test
    void threeConsecutiveCommFailures_openTheBreaker_andOpenAnswersLocallyWithoutContact() {
        RailGuard guard = new RailGuard(new RailGuard.Config(3, 50f, Duration.ofMinutes(1), 1));
        var capability = new RailCapability.Parallel(4, Duration.ofMillis(100), Duration.ofMillis(500));
        AtomicInteger attempts = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            RailResult r = guard.call("mtn-momo", "host-a", capability, () -> {
                attempts.incrementAndGet();
                return new RailResult.Failed(new RailResult.FailureKind.CommUnreachable(Duration.ZERO));
            });
            assertThat(r).isInstanceOf(RailResult.Failed.class);
        }
        assertThat(guard.isOpen("mtn-momo")).isTrue();

        // OPEN: the provider call is NEVER reached; answered locally as CommUnreachable.
        RailResult r4 = guard.call("mtn-momo", "host-a", capability, () -> {
            attempts.incrementAndGet();
            return ok();
        });
        assertThat(attempts.get()).isEqualTo(3);                       // zero provider contact
        assertThat(r4).isEqualTo(new RailResult.Failed(new RailResult.FailureKind.CommUnreachable(Duration.ZERO)));
    }

    @Test
    void schemeRejectionsNeverTripTheBreaker() {
        RailGuard guard = new RailGuard(RailGuard.Config.defaults());
        var capability = new RailCapability.Parallel(4, Duration.ofMillis(100), Duration.ofMillis(500));

        for (int i = 0; i < 10; i++) {
            guard.call("biller-x", "host-b", capability,
                    () -> new RailResult.Rejected("INVALID_ACCOUNT", true));
        }
        assertThat(guard.isOpen("biller-x")).isFalse();   // business event, not health
    }

    @Test
    void rateLimitFreezesTheWindow_andNeverRecordsBreakerFailures() {
        RailGuard guard = new RailGuard(RailGuard.Config.defaults());
        var capability = new RailCapability.Parallel(4, Duration.ofMillis(100), Duration.ofMillis(500));
        AtomicInteger failures = new AtomicInteger();

        // A provider answering 429 (Retry-After 60 s): window freezes, breaker untouched.
        RailResult r = guard.call("mtn-momo", "host-a", capability, () ->
                new RailResult.Failed(new RailResult.FailureKind.RateLimited(Duration.ofSeconds(60))));
        assertThat(((RailResult.Failed) r).kind())
                .isInstanceOf(RailResult.FailureKind.RateLimited.class);
        assertThat(guard.isOpen("mtn-momo")).isFalse();
        assertThat(guard.isThrottled("mtn-momo")).isTrue();

        // During the frozen window: refused locally, and the refusal trips nothing.
        assertThatThrownBy(() -> guard.call("mtn-momo", "host-a", capability, () -> {
            failures.incrementAndGet();
            return ok();
        })).isInstanceOf(RailTransportException.class)
                .extracting(e -> ((RailTransportException) e).kind())
                .isInstanceOf(RailResult.FailureKind.RateLimited.class);
        assertThat(failures.get()).isEqualTo(0);   // zero provider contact during reset
        assertThat(guard.isOpen("mtn-momo")).isFalse();
    }

    @Test
    void bulkheadRefusesImmediatelyOnceTheCapabilityCapIsReached() throws Exception {
        RailGuard guard = new RailGuard(RailGuard.Config.defaults());
        var capability = new RailCapability.Parallel(1, Duration.ofMillis(100), Duration.ofMillis(500));

        try (RailGuard.Admission lease = guard.acquire("wallet-rail", "host-c", capability)) {
            assertThatThrownBy(() -> guard.acquire("wallet-rail", "host-c", capability))
                    .isInstanceOf(RailTransportException.class)
                    .extracting(e -> ((RailTransportException) e).kind())
                    .isInstanceOf(RailResult.FailureKind.Backpressure.class);
        }
        // Released the lease: capacity is free again.
        RailGuard.Admission second = guard.acquire("wallet-rail", "host-c", capability);
        second.close();
    }

    @Test
    void sequentialRailsGetExactlyOneInFlightSlotPerHost() throws Exception {
        RailGuard guard = new RailGuard(RailGuard.Config.defaults());
        var sequential = new RailCapability.Sequential(1);

        try (RailGuard.Admission ignored = guard.acquire("nb-8583", "session-1", sequential)) {
            assertThatThrownBy(() -> guard.acquire("nb-8583", "session-1", sequential))
                    .isInstanceOf(RailTransportException.class)
                    .extracting(e -> ((RailTransportException) e).kind())
                    .isInstanceOf(RailResult.FailureKind.Backpressure.class);
        }
    }

    @Test
    void halfOpenProbeRunsAfterCooldown_thenHealthySuccessCloses_theBreaker() throws Exception {
        RailGuard guard = new RailGuard(new RailGuard.Config(3, 50f, Duration.ofMillis(200), 1));
        var capability = new RailCapability.Parallel(4, Duration.ofMillis(50), Duration.ofMillis(200));
        for (int i = 0; i < 3; i++) {
            guard.call("nb-rest", "host-d", capability, () ->
                    new RailResult.Failed(new RailResult.FailureKind.CommUnreachable(Duration.ofMillis(10))));
        }
        assertThat(guard.isOpen("nb-rest")).isTrue();
        Thread.sleep(250);  // cooldown elapses

        // One probe is permitted; a healthy answer closes the breaker.
        RailResult probe = guard.call("nb-rest", "host-d", capability, () -> ok());
        assertThat(probe).isInstanceOf(RailResult.Accepted.class);
        assertThat(guard.isOpen("nb-rest")).isFalse();
    }

    @Test
    void adapterThrownCommUnreachableCountsTowardTheBreaker() {
        RailGuard guard = new RailGuard(new RailGuard.Config(3, 50f, Duration.ofMinutes(1), 1));
        var capability = new RailCapability.StatusProbe(Duration.ofSeconds(5));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> guard.call("recon-probe", "host-e", capability, () -> {
                throw new RailTransportException(new RailResult.FailureKind.CommUnreachable(Duration.ZERO), null);
            })).isInstanceOf(RailTransportException.class);
        }
        assertThat(guard.isOpen("recon-probe")).isTrue();
    }
}
