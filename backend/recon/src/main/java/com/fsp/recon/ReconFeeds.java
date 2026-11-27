package com.fsp.recon;

import java.util.List;
import java.util.UUID;

/**
 * Feed DTOs mirroring the book-owner services' internal recon faces
 * ({@code /internal/recon/snapshot} on EMI :8086 and PSP :8085). Money is STRING on the
 * wire (platform AGENTS §2b) and is parsed here defensively — a malformed number is a
 * probe failure, never a default-to-zero.
 */
public final class ReconFeeds {

    /** PSP internal snapshot (backend/psp recon face). */
    public record PspPosition(String connectorCode, String currency, String positionDate,
                              String amountMinor, boolean settled, String tbNet) {}

    public record PspPayableOverdue(UUID merchantId, String currency, String periodStart,
                                    String accrualMinor, String paidMinor, int daysOver) {}

    public record PspFloatLine(String currency, String floatNetMinor) {}

    public record PspSnapshot(String generatedAt, List<PspPosition> positions,
                              List<PspPayableOverdue> payablesOverdue, long staleCaptures,
                              List<PspFloatLine> floats) {}

    /** EMI internal custody snapshot (backend/emi recon face). */
    public record EmiCustodyLine(String currency, long walletCount, String walletSumMinor,
                                 String heldSumMinor, String floatNetMinor, int walletsUnproven) {}

    public record EmiSnapshot(String generatedAt, List<EmiCustodyLine> custody) {}

    /** A failed invariant: keyed, classified, and (when P0) paged. */
    public record Violation(String invariant, String severity, String key, String detail) {}
}
