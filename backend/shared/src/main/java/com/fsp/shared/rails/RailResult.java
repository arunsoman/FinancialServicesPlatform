package com.fsp.shared.rails;

import java.time.Instant;
import java.time.Duration;

/**
 * Response taxonomy (plan §3.3) — sealed, same discipline as the protocol SPI.
 * Services never see HTTP status codes or exception traces; only these seals plus
 * {@link RailStatus} from {@code probe}.
 */
public sealed interface RailResult {

    record Accepted(String railRef, Instant settledAtOrEta) implements RailResult {}
    /** "Submitted, await confirm" — resolved later by {@code probe} or recon. */
    record AcceptedPending(String railRef) implements RailResult {}
    /** Definitive scheme decision — a business event, not a health event. */
    record Rejected(String code, boolean permanent) implements RailResult {}
    record Failed(FailureKind kind) implements RailResult {}

    /**
     * The failure alphabet a transport can produce. {@code Backpressure} is the one LOCAL
     * kind: the guard refused to send (bulkhead full / window reset) — no provider contact
     * happened, so it must never advance a saga's submitted-state machine or trip the breaker.
     */
    sealed interface FailureKind {
        record CommUnreachable(Duration observedFor) implements FailureKind {}
        record TemporarilyUnavailable(Integer retryAfterSeconds) implements FailureKind {}
        record RateLimited(Duration movingWindowReset) implements FailureKind {}
        /** Local: the connector guard refused admission (bulkhead/window) — plan §8.4. */
        record Backpressure(Duration retryIn) implements FailureKind {}
        record DefinitiveFailure(String railCode) implements FailureKind {}
    }
}
