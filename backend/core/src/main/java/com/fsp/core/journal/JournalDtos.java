package com.fsp.core.journal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Wire DTOs for the journal engine. Amounts are minor-unit strings (JS-safe, AGENTS.md §3.2). */
public final class JournalDtos {

    private JournalDtos() {}

    public record LineRequest(UUID glAccountId, String direction, String amount, String memo) {}

    public record CreateEntryRequest(UUID bookId, LocalDate postingDate, LocalDate valueDate,
                                    String description, String reference, List<LineRequest> lines) {}

    public record LineView(int lineNo, UUID glAccountId, String direction, String amount, String memo) {}

    public record EntryView(UUID entryId, UUID tenantId, UUID bookId, String currency,
                            String entryType, LocalDate postingDate, LocalDate valueDate,
                            String description, String reference,
                            String totalDebits, String totalCredits,
                            String status, String planHash, List<String> tbTransferIds,
                            UUID makerId, UUID checkerId, Instant finalizedAt, long version,
                            List<LineView> lines, UUID openApprovalId) {}

    public record PlanLegView(int legIndex, String transferId, UUID debitGlAccountId, UUID creditGlAccountId,
                              String amount, boolean linked) {}
}
