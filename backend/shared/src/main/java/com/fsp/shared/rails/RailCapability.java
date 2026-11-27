package com.fsp.shared.rails;

import java.time.Duration;

/**
 * What a protocol adapter can and cannot do — capability, not instance, drives dispatch
 * (plan §3.2). The {@code RailGuard} turns these into bulkhead/breaker configuration.
 */
public sealed interface RailCapability {

    /** Session-bound rails: exactly one in-flight message per session/terminal; everything else fails fast. */
    record Sequential(int maxInFlightPerSession) implements RailCapability {}

    /** HTTP rails: N parallel calls, per-provider cap, bounded timeouts. */
    record Parallel(int maxInFlight, Duration connectTimeout, Duration readTimeout) implements RailCapability {}

    /** Query-only rails: status checks are idempotent and cheap (recon probes). */
    record StatusProbe(Duration freshnessWindow) implements RailCapability {}
}
