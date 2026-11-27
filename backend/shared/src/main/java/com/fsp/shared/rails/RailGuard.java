package com.fsp.shared.rails;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

/**
 * Central admission discipline for every provider call (plan §8.2–8.4, chapter 13 §5):
 *
 * <ul>
 *   <li><b>Throttle check</b> — after a 429, the connector's window is frozen for
 *       {@code Retry-After}: calls fail locally with {@code RateLimited} until the window
 *       resets. A rate limit is a quota signal, never a breaker signal — the breaker is
 *       NOT recorded for throttle rejections.</li>
 *   <li><b>Circuit breaker</b> — per connector: 3 consecutive comm failures (COUNT_BASED
 *       window of 3, ≥2/3 ⇒ 50%+ open threshold) ⇒ OPEN; in OPEN every message fails
 *       locally with zero provider contact until the cooldown; one HALF_OPEN probe to
 *       close. Recorded failures are CommUnreachable and TemporarilyUnavailable —
 *       scheme rejections (Rejected / DefinitiveFailure) are business events, not health.</li>
 *   <li><b>Bulkhead</b> — per (connector, host) admission cap straight from
 *       {@link RailCapability}: {@code Sequential(n)} = n concurrent, {@code Parallel(n,...)}
 *       = n concurrent. Second caller over the cap fails fast with
 *       {@code FailureKind.Backpressure} — never queued, never corrupting a session.</li>
 * </ul>
 *
 * Thread-safety (platform §2.8): registries are {@code ConcurrentHashMap}s of
 * resilience4j's thread-safe objects; the throttle deadline is a CAS-updated volatile
 * field. No {@code synchronized}, no JVM/DB locks across I/O.
 */
public final class RailGuard {

    public record Config(
            int slidingWindowSize,
            float failureRateThreshold,
            Duration openCooldown,
            int permittedCallsInHalfOpen) {
        public static Config defaults() {
            return new Config(3, 50f, Duration.ofSeconds(30), 1);
        }
    }

    private final Config cfg;
    private final Clock clock;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();

    /** connectorId → throttle deadline (429 re-seed). Volatile field per entry, CAS swapped. */
    private static final class Throttle {
        volatile Instant throttledUntil = Instant.MIN;
    }
    private final ConcurrentHashMap<String, Throttle> throttles = new ConcurrentHashMap<>();
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<Throttle, Instant> THROTTLED_UNTIL =
            AtomicReferenceFieldUpdater.newUpdater(Throttle.class, Instant.class, "throttledUntil");

    public RailGuard(Config cfg, Clock clock) {
        this.cfg = cfg;
        this.clock = clock;
    }

    public RailGuard() {
        this(Config.defaults(), Clock.systemUTC());
    }

    public RailGuard(Config cfg) {
        this(cfg, Clock.systemUTC());
    }

    /**
     * Runs one provider call under the full admission ladder. Returns the adapter's
     * {@link RailResult}; throws {@link RailTransportException} when the guard itself
     * refuses (throttle frozen) — with {@code RateLimited} or {@code Backpressure}.
     */
    public RailResult call(String connectorId, String hostKey, RailCapability capability,
                           Supplier<RailResult> providerCall) {
        Throttle throttle = throttles.computeIfAbsent(connectorId, k -> new Throttle());
        Instant until = throttle.throttledUntil;
        Instant now = clock.instant();
        if (until.isAfter(now)) {
            throw new RailTransportException(
                    new RailResult.FailureKind.RateLimited(Duration.between(now, until).negated().abs()), null);
        }

        CircuitBreaker breaker = breakers.computeIfAbsent(connectorId, this::newBreaker);
        if (!breaker.tryAcquirePermission()) {
            // OPEN: zero provider contact, answered locally until cooldown (+1 probe).
            return new RailResult.Failed(new RailResult.FailureKind.CommUnreachable(Duration.ZERO));
        }
        long start = System.nanoTime();
        try (Admission lease = acquire(connectorId, hostKey, capability)) {
            RailResult result;
            try {
                result = providerCall.get();
            } catch (RailTransportException t) {
                recordException(breaker, elapsedNano(start), t);
                if (t.kind() instanceof RailResult.FailureKind.RateLimited window) {
                    reseedWindow(connectorId, window.movingWindowReset());
                }
                throw t;
            }
            record(breaker, elapsedNano(start), result);
            if (result instanceof RailResult.Failed k
                    && k.kind() instanceof RailResult.FailureKind.RateLimited window) {
                reseedWindow(connectorId, window.movingWindowReset());
            }
            return result;
        }
    }

    /** resilience4j 2.4 records call DURATION (not a start timestamp): elapsed since start. */
    private static long elapsedNano(long startedAtNano) {
        long elapsed = System.nanoTime() - startedAtNano;
        return Math.max(1, elapsed);
    }

    private void record(CircuitBreaker breaker, long start, RailResult result) {
        boolean unhealthy = result instanceof RailResult.Failed k && (
                k.kind() instanceof RailResult.FailureKind.CommUnreachable
                        || k.kind() instanceof RailResult.FailureKind.TemporarilyUnavailable);
        if (unhealthy) {
            breaker.onError(start, TimeUnit.NANOSECONDS,
                    new RailTransportException(((RailResult.Failed) result).kind(), null));
        } else {
            // Accepted / AcceptedPending / Rejected / scheme answers: health is fine.
            breaker.onSuccess(start, TimeUnit.NANOSECONDS);
        }
    }

    private void recordException(CircuitBreaker breaker, long start, RailTransportException t) {
        // RateLimited / Backpressure are quota/backpressure signals — never breaker failures.
        if (t.kind() instanceof RailResult.FailureKind.CommUnreachable
                || t.kind() instanceof RailResult.FailureKind.TemporarilyUnavailable) {
            breaker.onError(start, TimeUnit.NANOSECONDS, t);
        } else {
            breaker.onSuccess(start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Bulkhead admission lease for direct (non-{@link #call}) use — e.g. a saga engine
     * holding a session slot around a multi-step probe sequence. MUST be used in
     * try-with-resources; refuses immediately when the cap is reached.
     */
    public Admission acquire(String connectorId, String hostKey, RailCapability capability) {
        if (capability instanceof RailCapability.StatusProbe) {
            return Admission.EMPTY;   // probes are cheap and idempotent: no capacity admission
        }
        Bulkhead bulkhead = bulkheads.computeIfAbsent(bulkheadName(connectorId, hostKey),
                name -> newBulkhead(name, capability));
        try {
            bulkhead.acquirePermission();
            return bulkhead::releasePermission;
        } catch (BulkheadFullException e) {
            throw new RailTransportException(
                    new RailResult.FailureKind.Backpressure(Duration.ZERO), e);
        }
    }

    /** Functional interface + AutoCloseable wrapper so try-with-resources reads nicely. */
    @FunctionalInterface
    public interface Admission extends AutoCloseable {
        @Override
        void close(); // no checked exception

        Admission EMPTY = () -> { };
    }

    /** 429 handling (plan §8.4): freeze the connector's window from Retry-After. */
    public void reseedWindow(String connectorId, Duration windowReset) {
        Throttle throttle = throttles.computeIfAbsent(connectorId, k -> new Throttle());
        Instant until = clock.instant().plus(windowReset);
        THROTTLED_UNTIL.updateAndGet(throttle, current ->
                current.isAfter(until) ? current : until);
    }

    public boolean isThrottled(String connectorId) {
        return throttles.getOrDefault(connectorId, new Throttle()).throttledUntil
                .isAfter(clock.instant());
    }

    /** Test/ops visibility: is the connector's breaker open right now? */
    public boolean isOpen(String connectorId) {
        CircuitBreaker breaker = breakers.get(connectorId);
        return breaker != null && breaker.getState() == CircuitBreaker.State.OPEN;
    }

    private CircuitBreaker newBreaker(String connectorId) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cfg.slidingWindowSize())
                .minimumNumberOfCalls(cfg.slidingWindowSize())
                .failureRateThreshold(cfg.failureRateThreshold())
                .waitDurationInOpenState(cfg.openCooldown())
                .permittedNumberOfCallsInHalfOpenState(cfg.permittedCallsInHalfOpen())
                .build();
        return CircuitBreaker.of(connectorId, config);
    }

    private static String bulkheadName(String connectorId, String hostKey) {
        return connectorId + "|" + hostKey;
    }

    private Bulkhead newBulkhead(String name, RailCapability capability) {
        int maxInFlight = switch (capability) {
            case RailCapability.Sequential s -> Math.max(1, s.maxInFlightPerSession());
            case RailCapability.Parallel p -> Math.max(1, p.maxInFlight());
            case RailCapability.StatusProbe ignored -> -1; // handled by acquire(): probes take no bulkhead
        };
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(Math.max(1, maxInFlight))
                .maxWaitDuration(Duration.ZERO)   // fail fast, never queue into a session
                .fairCallHandlingStrategyEnabled(false)
                .writableStackTraceEnabled(false)
                .build();
        return Bulkhead.of(name, config);
    }
}
