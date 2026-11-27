package com.fsp.shared.rails;

/**
 * Transport-level status returned by {@link ProtocolAdapter#probe} — the ambiguity
 * resolver of the bill-payment ladder (plan §5.3). Idempotent and safe to call at any time.
 */
public enum RailStatus {
    /** Rail confirms the movement settled. */
    SETTLED,
    /** Rail confirms the movement is still in flight (or exists, unresolved). */
    IN_FLIGHT,
    /** Rail has no record of the reference — safe to void. */
    NOT_FOUND,
    /** Rail rejects the reference scheme (e.g. unknown format) — treat as definitive failure. */
    REJECTED_AT_RAIL
}
