package com.fsp.psp.recon;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only recon face (chapter 13 §8, plan Stage 6): the PSP service publishes what the
 * recon-worker needs to assert its invariants — unsettled clearing positions with their
 * TB posted net, overdue merchant payables (PSD-1 §12.2.3 pay-over), stale captures, and
 * the per-currency settlement float net. Served on the INTERNAL PLANE ONLY
 * ({@code /internal/recon/snapshot}; internal-credential hop, never routed by the gateway,
 * never in the operation catalog). Money is STRING on the wire (platform AGENTS §2b).
 */
public final class PspReconSnapshot {

    public record Position(String connectorCode, String currency, LocalDate positionDate,
                           String amountMinor, boolean settled, String tbNet) {}
                           // tbNet is null when the TB account cannot be proved (chapter 13 §8:
                           // unknown ≠ zero — the recon worker treats null as probe noise).

    /** Payable with an outstanding accrual past the merchant's pay-over ceiling. */
    public record PayableOverdue(UUID merchantId, String currency, LocalDate periodStart,
                                 String accrualMinor, String paidMinor, int daysOver) {}

    /** One currency line of the settlement float book (custody mirror of acquired funds). */
    public record FloatLine(String currency, String floatNetMinor) {}
                           // floatNetMinor null when the float account cannot be proved.

    public record Snapshot(Instant generatedAt, List<Position> positions,
                           List<PayableOverdue> payablesOverdue, long staleCaptures,
                           List<FloatLine> floats) {}
}
