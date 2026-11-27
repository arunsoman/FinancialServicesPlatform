package com.fsp.shared.rails;

/**
 * Typed transport failure for adapters: carrying this (instead of a naked
 * {@code IOException}) is how a connector speaks the failure alphabet. The
 * {@link RailGuard} records CommUnreachable / TemporarilyUnavailable as breaker
 * failures and records everything else as scheme decisions.
 */
public class RailTransportException extends RuntimeException {
    private final RailResult.FailureKind kind;

    public RailTransportException(RailResult.FailureKind kind, Throwable cause) {
        super(kind.getClass().getSimpleName(), cause, false, true);
        this.kind = kind;
    }

    public RailResult.FailureKind kind() {
        return kind;
    }
}
