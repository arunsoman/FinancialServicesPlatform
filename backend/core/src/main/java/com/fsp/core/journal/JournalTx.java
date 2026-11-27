package com.fsp.core.journal;

import com.fsp.core.audit.AuditService;
import com.fsp.core.common.ApiException;
import com.fsp.core.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Transactional state transitions for the posting state machine.
 * Kept in a separate bean so JournalService's non-in-transaction orchestration
 * (TigerBeetle calls) still gets proxy-based transactions (self-invocation bypasses @Transactional).
 */
@Slf4j
@Service
public class JournalTx {

    private final JournalEntryRepository entryRepository;
    private final JournalLineRepository lineRepository;
    private final AuditService audit;
    private final OutboxService outbox;

    public JournalTx(JournalEntryRepository entryRepository,
                     JournalLineRepository lineRepository,
                     AuditService audit, OutboxService outbox) {
        this.entryRepository = entryRepository;
        this.lineRepository = lineRepository;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record Snapshot(UUID entryId, UUID tenantId, UUID bookId, String currency,
                           String status, String planHash, List<String> transferIds,
                           UUID makerId, UUID checkerId, Instant finalizedAt, long version) {

        public String payload() {
            return "{\"entryId\":\"%s\",\"tenantId\":\"%s\",\"bookId\":\"%s\",\"status\":\"%s\",\"planHash\":\"%s\"}"
                .formatted(entryId, tenantId, bookId, status, planHash == null ? "" : planHash);
        }
    }

    /** READY: checker set, canonical plan persisted (the recovery anchor). */
    @Transactional
    public Snapshot saveReady(UUID entryId, String planHash, List<String> transferIds, UUID checkerId) {
        JournalEntry je = loaded(entryId);
        precondition(je, JournalEntry.Status.PENDING_CHECKER, "READY");
        je.checkerId = checkerId;
        je.planHash = planHash;
        je.tbTransferIds = String.join(",", transferIds);
        je.status = JournalEntry.Status.READY;
        save(je);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_APPROVAL_RESOLVED",
            "JOURNAL_ENTRY", je.id.toString(),
            audit.hashState("status=PENDING_CHECKER"), audit.hashState("status=READY"), false);
        return snapshot(je);
    }

    /** Posting intents: a validated, already-planned entry enters the state machine at
     *  PENDING_CHECKER — the acting person occupies both maker and checker planes by design
     *  (je.post.immediate is a separately-grantable permission; plan persisted in the same tx). */
    @Transactional
    public Snapshot persistIntent(JournalEntry entry, List<JournalLine> lines) {
        entryRepository.save(entry);
        lineRepository.saveAll(lines);
        audit.record(entry.makerId, null, entry.tenantId, entry.bookId, "JE_INTENT_PREPARED", "JOURNAL_ENTRY",
            entry.id.toString(), audit.hashState("null"), audit.hashState("status=PENDING_CHECKER"), false);
        outbox.record("JournalEntry", entry.id, "JournalEntry.IntentPrepared", snapshot(entry).payload());
        return snapshot(entry);
    }

    /** COMMIT_UNKNOWN → FINALIZED after lookup confirmed the original ids (recovery path). */
    @Transactional
    public Snapshot finalizeFromUnknown(UUID entryId) {
        JournalEntry je = loaded(entryId);
        precondition(je, JournalEntry.Status.COMMIT_UNKNOWN, "FINALIZED");
        je.status = JournalEntry.Status.FINALIZED;
        je.finalizedAt = Instant.now();
        save(je);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_RECOVERY_RESOLVED", "JOURNAL_ENTRY",
            je.id.toString(), audit.hashState("status=COMMIT_UNKNOWN"), audit.hashState("status=FINALIZED"), false);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_FINALIZED", "JOURNAL_ENTRY",
            je.id.toString(), audit.hashState("status=FINALIZED"), audit.hashState("status=FINALIZED"), false);
        outbox.record("JournalEntry", je.id, "JournalEntry.Posted", snapshot(je).payload());
        outbox.record("JournalEntry", je.id, "PostingGroup.Finalized", snapshot(je).payload());
        return snapshot(je);
    }

    /** READY → FINALIZED on the happy path (commit confirmation + finalization evidence). */
    @Transactional
    public Snapshot finalizeFromReady(UUID entryId) {
        JournalEntry je = loaded(entryId);
        precondition(je, JournalEntry.Status.READY, "FINALIZED");
        je.status = JournalEntry.Status.FINALIZED;
        je.finalizedAt = Instant.now();
        save(je);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_COMMIT_CONFIRMED", "JOURNAL_ENTRY",
            je.id.toString(), audit.hashState("status=READY"), audit.hashState("status=COMMITTED"), false);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_FINALIZED", "JOURNAL_ENTRY",
            je.id.toString(), audit.hashState("status=COMMITTED"), audit.hashState("status=FINALIZED"), false);
        outbox.record("JournalEntry", je.id, "PostingGroup.Committed", snapshot(je).payload());
        outbox.record("JournalEntry", je.id, "JournalEntry.Posted", snapshot(je).payload());
        outbox.record("JournalEntry", je.id, "PostingGroup.Finalized", snapshot(je).payload());
        return snapshot(je);
    }

    /** READY → REJECTED (definitive TigerBeetle rejection). */
    @Transactional
    public Snapshot rejectFromReady(UUID entryId, String detail) {
        JournalEntry je = loaded(entryId);
        precondition(je, JournalEntry.Status.READY, "REJECTED");
        je.status = JournalEntry.Status.REJECTED;
        save(je);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_REJECTED_BY_LEDGER", "JOURNAL_ENTRY",
            je.id.toString(), audit.hashState("status=READY"), audit.hashState("status=REJECTED;" + detail), false);
        outbox.record("JournalEntry", je.id, "JournalEntry.Rejected", snapshot(je).payload());
        return snapshot(je);
    }

    /** COMMIT_UNKNOWN → REJECTED (recovery discovered the ids unusable / retry rejected). */
    @Transactional
    public Snapshot rejectFromUnknown(UUID entryId, String detail) {
        JournalEntry je = loaded(entryId);
        precondition(je, JournalEntry.Status.COMMIT_UNKNOWN, "REJECTED");
        je.status = JournalEntry.Status.REJECTED;
        save(je);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_RECOVERY_RESOLVED", "JOURNAL_ENTRY",
            je.id.toString(), audit.hashState("status=COMMIT_UNKNOWN"), audit.hashState("status=REJECTED"), false);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_REJECTED_BY_LEDGER", "JOURNAL_ENTRY",
            je.id.toString(), audit.hashState("status=REJECTED"), audit.hashState("status=REJECTED;" + detail), false);
        outbox.record("JournalEntry", je.id, "JournalEntry.Rejected", snapshot(je).payload());
        return snapshot(je);
    }

    /** READY → COMMIT_UNKNOWN (lost response / injected transport failure). */
    @Transactional
    public Snapshot markUnknown(UUID entryId, String detail) {
        JournalEntry je = loaded(entryId);
        precondition(je, JournalEntry.Status.READY, "COMMIT_UNKNOWN");
        je.status = JournalEntry.Status.COMMIT_UNKNOWN;
        save(je);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_COMMIT_UNKNOWN", "JOURNAL_ENTRY",
            je.id.toString(), audit.hashState("status=READY"), audit.hashState("status=COMMIT_UNKNOWN"), false);
        outbox.record("JournalEntry", je.id, "PostingGroup.CommitUnknown", snapshot(je).payload());
        return snapshot(je);
    }

    /** Recovery retry also lost: stay COMMIT_UNKNOWN, record the attempt. */
    @Transactional
    public Snapshot noteRecoveryRetryUnknown(UUID entryId, String correlationNote) {
        JournalEntry je = loaded(entryId);
        precondition(je, JournalEntry.Status.COMMIT_UNKNOWN, "COMMIT_UNKNOWN");
        save(je);
        audit.record(je.makerId, je.checkerId, je.tenantId, je.bookId, "JE_RECOVERY_RETRY_UNKNOWN",
            "JOURNAL_ENTRY", je.id.toString(), audit.hashState("status=COMMIT_UNKNOWN"),
            audit.hashState("status=COMMIT_UNKNOWN;" + correlationNote), false);
        return snapshot(je);
    }

    // ---------- helpers ----------

    private JournalEntry loaded(UUID entryId) {
        return entryRepository.findById(entryId)
            .orElseThrow(() -> ApiException.notFound("JE_NOT_FOUND", "Journal entry missing during transition"));
    }

    private static void precondition(JournalEntry je, String expected, String target) {
        if (!expected.equals(je.status)) {
            log.warn("je_transition_conflict entryId={} expected={} actual={} target={}",
                je.id, expected, je.status, target);
            throw ApiException.conflict("JE_STATE",
                "Expected status %s to reach %s but found %s".formatted(expected, target, je.status));
        }
    }

    private void save(JournalEntry je) {
        entryRepository.save(je);
    }

    private Snapshot snapshot(JournalEntry je) {
        List<String> ids = (je.tbTransferIds == null || je.tbTransferIds.isBlank())
            ? List.of()
            : Arrays.stream(je.tbTransferIds.split(",")).filter(s -> !s.isBlank()).toList();
        return new Snapshot(je.id, je.tenantId, je.bookId, je.currency, je.status, je.planHash,
            ids, je.makerId, je.checkerId, je.finalizedAt, je.version);
    }

    @SuppressWarnings("unused")
    private List<JournalLine> lines(UUID entryId) {
        return lineRepository.findByEntryIdOrderByLineNoAsc(entryId);
    }
}
