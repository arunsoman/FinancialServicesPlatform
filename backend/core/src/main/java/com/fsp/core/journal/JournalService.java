package com.fsp.core.journal;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.KeyLocks;
import com.fsp.core.config.FspProperties;

import com.fsp.core.ledger.AccountingPeriodRepository;
import com.fsp.core.ledger.BookRepository;
import com.fsp.core.ledger.GlAccountRepository;
import com.fsp.core.ledger.TbResult;
import com.fsp.core.ledger.TbService;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Journal entry engine orchestrator.
 * Happy path: DRAFT → PENDING_CHECKER → READY (plan persisted in tx) →
 *   TigerBeetle submit (out of tx) → sealed PostingOutcome → terminal state in tx.
 * Recovery (check-status): COMMIT_UNKNOWN resolved with the ORIGINAL transfer ids.
 */
@Slf4j
@Service
public class JournalService {

    private final JournalEntryRepository entryRepository;
    private final JournalLineRepository lineRepository;
    private final BookRepository bookRepository;
    private final GlAccountRepository glAccountRepository;
    private final AccountingPeriodRepository periodRepository;
    private final TbService tbService;
    private final PermissionService permissions;
    private final AuditService audit;
    private final OutboxService outbox;
    private final JournalTx journalTx;
    private final ApprovalService approvalService;
    private final KeyLocks keyLocks;
    private final FspProperties props;

    public JournalService(JournalEntryRepository entryRepository,
                          JournalLineRepository lineRepository,
                          BookRepository bookRepository,
                          GlAccountRepository glAccountRepository,
                          AccountingPeriodRepository periodRepository,
                          TbService tbService, PermissionService permissions,
                          AuditService audit, OutboxService outbox, JournalTx journalTx,
                          ApprovalService approvalService, KeyLocks keyLocks, FspProperties props) {
        this.entryRepository = entryRepository;
        this.lineRepository = lineRepository;
        this.bookRepository = bookRepository;
        this.glAccountRepository = glAccountRepository;
        this.periodRepository = periodRepository;
        this.tbService = tbService;
        this.permissions = permissions;
        this.audit = audit;
        this.outbox = outbox;
        this.journalTx = journalTx;
        this.approvalService = approvalService;
        this.keyLocks = keyLocks;
        this.props = props;
    }

    // ---------------- DRAFT (maker) ----------------

    @Transactional
    public JournalDtos.EntryView createDraft(UUID tenantId, JournalDtos.CreateEntryRequest req, Actor actor) {
        permissions.require(actor, "je.create", tenantId);

        var book = bookRepository.findById(req.bookId())
            .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        if (!book.tenantId.equals(tenantId)) throw ApiException.forbidden("TENANT_MISMATCH", "Book out of scope");
        if (req.lines() == null || req.lines().size() < 2) {
            throw ApiException.badRequest("JE_LINES", "At least 2 lines are required");
        }
        if (req.postingDate() == null) throw ApiException.badRequest("JE_DATE", "postingDate is required");

        List<ParsedLine> parsed = parseLines(req.lines(), book);

        // posting fence (Phase-0 simplification): posting date must fall inside an OPEN period of the book
        List<com.fsp.core.ledger.AccountingPeriod> periods =
            periodRepository.findByBookIdOrderByFiscalYearDescPeriodNoDesc(book.id);
        List<com.fsp.core.ledger.AccountingPeriod> open = periods.stream()
            .filter(p -> "OPEN".equals(p.status))
            .filter(p -> !req.postingDate().isBefore(p.startDate))
            .filter(p -> !req.postingDate().isAfter(p.endDate))
            .toList();
        if (open.isEmpty()) {
            throw ApiException.conflict("PERIOD_NOT_OPEN",
                "No OPEN accounting period covers " + req.postingDate() + " for book " + book.id);
        }

        long debitsTotal = parsed.stream().filter(l -> l.direction().equals("DEBIT"))
            .mapToLong(ParsedLine::amount).sum();
        long creditsTotal = parsed.stream().filter(l -> l.direction().equals("CREDIT"))
            .mapToLong(ParsedLine::amount).sum();
        if (debitsTotal == 0 || debitsTotal != creditsTotal) {
            throw ApiException.badRequest("JE_NOT_BALANCED",
                "debits=%d credits=%d — a JE must balance within its book and currency".formatted(debitsTotal, creditsTotal));
        }

        // distinct-account same-side rule is enforced by the planner at approval time;
        // reject same-account-on-both-sides early for a clearer maker error
        var debitAccounts = parsed.stream().filter(l -> l.direction().equals("DEBIT"))
            .map(ParsedLine::glAccountId).distinct().toList();
        parsed.stream().filter(l -> l.direction().equals("CREDIT"))
            .map(ParsedLine::glAccountId).distinct()
            .filter(debitAccounts::contains)
            .findFirst()
            .ifPresent(a -> { throw ApiException.badRequest("JE_SAME_ACCOUNT",
                "Same GL account on both sides is not allowed"); });

        JournalEntry entry = new JournalEntry();
        entry.id = UUID.randomUUID();
        entry.tenantId = tenantId;
        entry.bookId = book.id;
        entry.periodId = open.getFirst().id;
        entry.postingDate = req.postingDate();
        entry.valueDate = req.valueDate() == null ? req.postingDate() : req.valueDate();
        entry.description = req.description();
        entry.reference = req.reference();
        entry.currency = book.currency;
        entry.makerId = actor.personId();
        entry.status = JournalEntry.Status.DRAFT;
        entry.totalDebits = BigDecimal.valueOf(debitsTotal);
        entry.totalCredits = BigDecimal.valueOf(creditsTotal);
        entryRepository.save(entry);
        log.debug("je_drafted entryId={} bookId={} lines={} debits={} credits={}",
            entry.id, entry.bookId, parsed.size(), debitsTotal, creditsTotal);

        List<JournalLine> lines = new ArrayList<>(parsed.size());
        int lineNo = 1;
        for (ParsedLine p : parsed) {
            JournalLine line = new JournalLine();
            line.id = UUID.randomUUID();
            line.entryId = entry.id;
            line.lineNo = lineNo++;
            line.glAccountId = p.glAccountId();
            line.direction = p.direction();
            line.amount = BigDecimal.valueOf(p.amount());
            line.memo = p.memo();
            lines.add(line);
        }
        lineRepository.saveAll(lines);

        audit.record(actor.personId(), null, tenantId, book.id, "JE_DRAFTED", "JOURNAL_ENTRY",
            entry.id.toString(), audit.hashState("null"), audit.hashState("status=DRAFT"), false);
        outbox.record("JournalEntry", entry.id, "JournalEntry.Drafted", txSnapshot(entry).payload());
        return view(entry, lines);
    }

    @Transactional
    public JournalDtos.EntryView submitForApproval(UUID tenantId, UUID entryId, Actor actor) {
        permissions.require(actor, "je.create", tenantId);
        JournalEntry entry = entryRepository.findByIdAndTenantId(entryId, tenantId)
            .orElseThrow(() -> ApiException.notFound("JE_NOT_FOUND", "Journal entry not found"));
        if (!JournalEntry.Status.DRAFT.equals(entry.status)) {
            throw ApiException.conflict("JE_STATE",
                "Only DRAFT entries can be submitted for approval (current: %s)".formatted(entry.status));
        }
        if (!entry.makerId.equals(actor.personId())) {
            throw ApiException.forbidden("MAKER_ONLY", "Only the maker can submit their draft for approval");
        }
        entry.status = JournalEntry.Status.PENDING_CHECKER;
        entryRepository.save(entry);
        log.info("je_submitted entryId={} makerId={} for four-eyes approval", entry.id, entry.makerId);
        audit.record(actor.personId(), null, tenantId, entry.bookId, "JE_SUBMITTED", "JOURNAL_ENTRY",
            entry.id.toString(), audit.hashState("status=DRAFT"), audit.hashState("status=PENDING_CHECKER"), false);
        outbox.record("JournalEntry", entry.id, "JournalEntry.Submitted", txSnapshot(entry).payload());
        ApprovalRequest approval = approvalService.open(tenantId, ApprovalRequest.OpType.JE_POSTING, entryId,
            actor, "entry:" + entryId, null);
        List<JournalLine> submitted = lineRepository.findByEntryIdOrderByLineNoAsc(entry.id);
        var v = view(entry, submitted);
        return new JournalDtos.EntryView(v.entryId(), v.tenantId(), v.bookId(), v.currency(), v.entryType(),
            v.postingDate(), v.valueDate(), v.description(), v.reference(), v.totalDebits(), v.totalCredits(),
            v.status(), v.planHash(), v.tbTransferIds(), v.makerId(), v.checkerId(), v.finalizedAt(),
            v.version(), v.lines(), approval.id);
    }

    // ---------------- posting intents (single-actor fast path, plans/inventory-plugin-plan.md D3) ----------------

    /**
     * POST /v1/tenants/{tenantId}/journal/intents — one-actor immediate posting for flows that
     * deliberately configure no per-item four-eyes (POS/airtime retail dispense)
     * (plans/inventory-plugin-plan.md D3).
     * Money invariants are NOT relaxed: the JE must balance, fall inside an OPEN period, plan,
     * and dispatch through the SAME PostingPlanner + JournalTx + KeyLocks pipeline; it is audited
     * with the acting person (je.post.immediate must be specifically granted).
     * Orchestration mirrors approveAndPost: validate + plan (no tx) → persist PENDING_CHECKER (tx,
     * via JournalTx) → lock → saveReady (tx) → dispatch (no tx) → terminal transition (tx).
     */
    public JournalDtos.EntryView postIntent(UUID tenantId, JournalDtos.CreateEntryRequest req, Actor actor) {
        permissions.require(actor, "je.post.immediate", tenantId);
        var book = bookRepository.findById(req.bookId())
            .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        if (!book.tenantId.equals(tenantId)) throw ApiException.forbidden("TENANT_MISMATCH", "Book out of scope");
        if (req.lines() == null || req.lines().size() < 2) {
            throw ApiException.badRequest("JE_LINES", "At least 2 lines are required");
        }
        if (req.postingDate() == null) throw ApiException.badRequest("JE_DATE", "postingDate is required");

        List<ParsedLine> parsed = parseLines(req.lines(), book);
        UUID periodId = openPeriodId(book.id, req.postingDate());

        long debitsTotal = parsed.stream().filter(l -> l.direction().equals("DEBIT"))
            .mapToLong(ParsedLine::amount).sum();
        long creditsTotal = parsed.stream().filter(l -> l.direction().equals("CREDIT"))
            .mapToLong(ParsedLine::amount).sum();
        if (debitsTotal == 0 || debitsTotal != creditsTotal) {
            throw ApiException.badRequest("JE_NOT_BALANCED",
                "debits=%d credits=%d — a JE must balance within its book and currency".formatted(debitsTotal, creditsTotal));
        }
        var debitAccounts = parsed.stream().filter(l -> l.direction().equals("DEBIT"))
            .map(ParsedLine::glAccountId).distinct().toList();
        parsed.stream().filter(l -> l.direction().equals("CREDIT"))
            .map(ParsedLine::glAccountId).distinct()
            .filter(debitAccounts::contains)
            .findFirst()
            .ifPresent(a -> { throw ApiException.badRequest("JE_SAME_ACCOUNT",
                "Same GL account on both sides is not allowed"); });

        JournalEntry entry = new JournalEntry();
        entry.id = UUID.randomUUID();
        entry.tenantId = tenantId;
        entry.bookId = book.id;
        entry.periodId = periodId;
        entry.entryType = "INTENT";
        entry.postingDate = req.postingDate();
        entry.valueDate = req.valueDate() == null ? req.postingDate() : req.valueDate();
        entry.description = req.description();
        entry.reference = req.reference();
        entry.currency = book.currency;
        entry.makerId = actor.personId();
        entry.totalDebits = BigDecimal.valueOf(debitsTotal);
        entry.totalCredits = BigDecimal.valueOf(creditsTotal);

        List<JournalLine> lines = new ArrayList<>(parsed.size());
        int lineNo = 1;
        for (ParsedLine p : parsed) {
            JournalLine line = new JournalLine();
            line.id = UUID.randomUUID();
            line.entryId = entry.id;
            line.lineNo = lineNo++;
            line.glAccountId = p.glAccountId();
            line.direction = p.direction();
            line.amount = BigDecimal.valueOf(p.amount());
            line.memo = p.memo();
            lines.add(line);
        }

        PostingPlanner.Plan plan;
        try {
            plan = PostingPlanner.plan(entry.id, specs(lines));
        } catch (PostingPlanner.JeInvariantException e) {
            throw ApiException.badRequest("JE_PLAN_INVALID", e.getMessage());
        }
        List<String> legIds = plan.legs().stream().map(l -> l.transferId().toString()).toList();

        journalTx.persistIntent(entry, lines);
        log.info("je_intent_prepared entryId={} reference={} legs={}", entry.id, req.reference(), legIds.size());

        JournalDtos.EntryView view = keyLocks.withLock("je-approval", entry.id, () -> {
            // dispatch serialization identical to the approval path; saveReady's DB status
            // precondition (PENDING_CHECKER) is the cross-instance backstop
            journalTx.saveReady(entry.id, plan.planHash(), legIds, actor.personId());
            PostingOutcome outcome = dispatch(book, entry, plan, lines);
            return switch (outcome) {
                case PostingOutcome.Committed ignored -> viewOf(journalTx.finalizeFromReady(entry.id));
                case PostingOutcome.Rejected rejected -> viewOf(journalTx.rejectFromReady(entry.id, rejected.detail()));
                case PostingOutcome.Unknown ignored -> viewOf(journalTx.markUnknown(entry.id, "response lost"));
            };
        });
        switch (view.status()) {
            case "FINALIZED" -> log.info("je_intent_committed entryId={} reference={} legs={}",
                entry.id, req.reference(), legIds.size());
            case "REJECTED" -> log.warn("je_intent_posting_rejected entryId={} reference={} status={}",
                entry.id, req.reference(), view.status());
            default -> log.error("je_commit_unknown entryId={} reference={} status={} — awaiting check-status",
                entry.id, req.reference(), view.status());
        }
        audit.record(actor.personId(), null, tenantId, entry.bookId, "JE_INTENT_POSTED", "JOURNAL_ENTRY",
            entry.id.toString(), audit.hashState("prepared"),
            audit.hashState("status=" + view.status() + ";reference=" + req.reference()), false);
        return view;
    }

    /** OPEN-period fence: an intent (like a draft) may only post inside an OPEN period. */
    private UUID openPeriodId(UUID bookId, LocalDate postingDate) {
        return periodRepository.findByBookIdOrderByFiscalYearDescPeriodNoDesc(bookId).stream()
            .filter(p -> "OPEN".equals(p.status))
            .filter(p -> !postingDate.isBefore(p.startDate))
            .filter(p -> !postingDate.isAfter(p.endDate))
            .findFirst()
            .map(p -> p.id)
            .orElseThrow(() -> ApiException.conflict("PERIOD_NOT_OPEN",
                "No OPEN accounting period covers " + postingDate + " for book " + bookId));
    }

    // ---------------- approve (checker): plan persisted BEFORE submission ----------------

    /** Four-eyes approval. The whole dispatch section serializes per entry id (AGENTS.md §2d):
     *  two checkers racing on the same entry must not both reach TigerBeetle. The DB status
     *  precondition inside JournalTx remains the cross-instance backstop. */
    public JournalDtos.EntryView approveAndPost(UUID tenantId, UUID entryId, Actor actor) {
        return keyLocks.withLock("je-approval", entryId, () -> approveAndPostLocked(tenantId, entryId, actor));
    }

    private JournalDtos.EntryView approveAndPostLocked(UUID tenantId, UUID entryId, Actor actor) {
        permissions.require(actor, "je.approve", tenantId);
        JournalEntry entry = entryRepository.findByIdAndTenantId(entryId, tenantId)
            .orElseThrow(() -> ApiException.notFound("JE_NOT_FOUND", "Journal entry not found"));
        if (!JournalEntry.Status.PENDING_CHECKER.equals(entry.status)) {
            throw ApiException.conflict("JE_STATE",
                "Only PENDING_CHECKER entries can be approved (current: %s)".formatted(entry.status));
        }
        permissions.requireDistinctPerson(actor, entry.makerId);

        List<JournalLine> lines = lineRepository.findByEntryIdOrderByLineNoAsc(entry.id);
        PostingPlanner.Plan plan;
        try {
            plan = PostingPlanner.plan(entry.id, specs(lines));
        } catch (PostingPlanner.JeInvariantException e) {
            throw ApiException.badRequest("JE_PLAN_INVALID", e.getMessage());
        }
        List<String> legIds = plan.legs().stream().map(l -> l.transferId().toString()).toList();

        // four-eyes evidence: resolves the JE_POSTING approval row (maker≠checker enforced there,
        // no-op-safe when the checker already resolved it from the queue)
        approvalService.resolveByTarget(tenantId, ApprovalRequest.OpType.JE_POSTING, entryId, true,
            actor, "checker-approved");

        // tx1: READY + canonical plan persisted (the recovery anchor)
        JournalTx.Snapshot ready = journalTx.saveReady(entryId, plan.planHash(), legIds, actor.personId());
        log.info("je_plan_persisted entryId={} planHash={} legs={}", entryId, plan.planHash(), plan.legs().size());
        var book = bookRepository.findById(entry.bookId)
            .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book deleted during approval"));

        // dispatch (out of tx) — sealed outcome drives the terminal state
        PostingOutcome outcome = dispatch(book, entry, plan, lines);
        JournalDtos.EntryView view = switch (outcome) {
            case PostingOutcome.Committed ignored -> viewOf(journalTx.finalizeFromReady(entryId));
            case PostingOutcome.Rejected rejected -> viewOf(journalTx.rejectFromReady(entryId, rejected.detail()));
            case PostingOutcome.Unknown ignored -> viewOf(journalTx.markUnknown(entryId, "response lost"));
        };
        switch (outcome) {
            case PostingOutcome.Committed c -> log.info("je_posting_committed entryId={} legs={}",
                entryId, c.transferIds().size());
            case PostingOutcome.Rejected r -> log.warn("je_posting_rejected entryId={} detail={}", entryId, r.detail());
            case PostingOutcome.Unknown u -> log.error("je_commit_unknown entryId={} planHash={} detail={} — awaiting check-status",
                entryId, plan.planHash(), u.detail());
        }
        return view;
    }

    /** Checker rejection from the approval queue (approval handler dispatch). */
    @Transactional
    public JournalDtos.EntryView rejectByChecker(UUID tenantId, UUID entryId, Actor checker, String reason) {
        permissions.require(checker, "je.approve", tenantId);
        JournalEntry entry = entryRepository.findByIdAndTenantId(entryId, tenantId)
            .orElseThrow(() -> ApiException.notFound("JE_NOT_FOUND", "Journal entry not found"));
        permissions.requireDistinctPerson(checker, entry.makerId);
        if (!JournalEntry.Status.PENDING_CHECKER.equals(entry.status)) {
            log.warn("je_transition_conflict entryId={} expected={} actual={} target=REJECTED",
                entryId, JournalEntry.Status.PENDING_CHECKER, entry.status);
            throw ApiException.conflict("JE_STATE",
                "Only PENDING_CHECKER entries can be rejected (current: %s)".formatted(entry.status));
        }
        entry.status = JournalEntry.Status.REJECTED;
        entryRepository.save(entry);
        audit.record(entry.makerId, checker.personId(), tenantId, entry.bookId, "JE_REJECTED_BY_CHECKER",
            "JOURNAL_ENTRY", entry.id.toString(), audit.hashState("status=PENDING_CHECKER"),
            audit.hashState("status=REJECTED;" + reason), false);
        approvalService.resolveByTarget(tenantId, ApprovalRequest.OpType.JE_POSTING, entryId, false,
            checker, reason);
        log.warn("je_rejected_by_checker entryId={} checker={} reason={}", entryId, checker.personId(), reason);
        return viewOf(entry);
    }

    // ---------------- COMMIT_UNKNOWN recovery (check-status) ----------------

    /** Recovery serialization per entry id (AGENTS.md §2d): concurrent check-status calls
     *  would otherwise double-dispatch the original transfer ids. */
    public JournalDtos.EntryView checkStatus(UUID tenantId, UUID entryId, Actor actor) {
        return keyLocks.withLock("je-recovery", entryId, () -> checkStatusLocked(tenantId, entryId, actor));
    }

    private JournalDtos.EntryView checkStatusLocked(UUID tenantId, UUID entryId, Actor actor) {
        permissions.require(actor, "je.approve", tenantId);
        JournalEntry entry = entryRepository.findByIdAndTenantId(entryId, tenantId)
            .orElseThrow(() -> ApiException.notFound("JE_NOT_FOUND", "Journal entry not found"));
        if (!JournalEntry.Status.COMMIT_UNKNOWN.equals(entry.status)) {
            throw ApiException.conflict("JE_STATE",
                "check-status applies only to COMMIT_UNKNOWN entries (current: %s)".formatted(entry.status));
        }

        List<JournalLine> lines = lineRepository.findByEntryIdOrderByLineNoAsc(entry.id);
        PostingPlanner.Plan plan;
        try {
            plan = PostingPlanner.plan(entry.id, specs(lines));
        } catch (PostingPlanner.JeInvariantException e) {
            throw ApiException.conflict("JE_RECOVERY_P1", "Persisted lines no longer plan: " + e.getMessage());
        }
        List<UUID> recomputed = plan.legs().stream().map(PostingPlanner.PlannedLeg::transferId).toList();

        List<String> stored = Arrays.stream(entry.tbTransferIds.split(","))
            .filter(s -> !s.isBlank()).toList();
        List<String> expected = recomputed.stream().map(UUID::toString).toList();
        if (!stored.equals(expected)) {
            log.error("je_recovery_p1_plan_mismatch entryId={} storedLegs={} recomputedLegs={} — money integrity incident",
                entryId, stored.size(), expected.size());
            audit.record(entry.makerId, entry.checkerId, tenantId, entry.bookId, "JE_RECOVERY_P1_PLAN_MISMATCH",
                "JOURNAL_ENTRY", entry.id.toString(), audit.hashState(String.join(",", stored)),
                audit.hashState(String.join(",", expected)), false);
            throw ApiException.serviceUnavailable("JE_RECOVERY_P1",
                "Persisted plan does not match recomputed legs; P1 raised");
        }

        boolean[] exists = tbService.transfersExist(recomputed);
        boolean all = allSet(exists);
        boolean none = noneSet(exists);
        log.debug("je_recovery_lookup entryId={} legs={} existsMask={}", entryId, recomputed.size(),
            Arrays.toString(exists));

        if (all) {
            log.info("je_recovery_resolved entryId={} outcome=COMMITTED (original transfer ids confirmed)", entryId);
            return viewOf(journalTx.finalizeFromUnknown(entryId));
        }

        var book = bookRepository.findById(entry.bookId)
            .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book deleted"));

        if (none) {
            // resubmit EXACTLY the original ids — idempotent in TigerBeetle; no fresh ids while uncertain
            PostingOutcome outcome = dispatch(book, entry, plan, lines);
            return switch (outcome) {
                case PostingOutcome.Committed ignored -> viewOf(journalTx.finalizeFromUnknown(entryId));
                case PostingOutcome.Rejected rejected -> viewOf(journalTx.rejectFromUnknown(entryId, rejected.detail()));
                case PostingOutcome.Unknown ignored ->
                    viewOf(journalTx.noteRecoveryRetryUnknown(entryId, "retry-transport"));
            };
        }

        // partial linked-chain state must not exist; treat as P1 and stay unresolved
        log.error("je_recovery_p1_partial_chain entryId={} legs={} existsMask={} — linked chain partially present", 
            entryId, recomputed.size(), Arrays.toString(exists));
        audit.record(entry.makerId, entry.checkerId, tenantId, entry.bookId, "JE_RECOVERY_P1_PARTIAL",
            "JOURNAL_ENTRY", entry.id.toString(), audit.hashState(plan.planHash()),
            audit.hashState("PARTIAL_LINKED_CHAIN"), false);
        throw ApiException.serviceUnavailable("JE_RECOVERY_P1",
            "Partial linked-chain state; P1 raised, outcome stays COMMIT_UNKNOWN");
    }

    // ---------------- queries ----------------

    public List<JournalDtos.EntryView> list(UUID tenantId, List<String> statuses, Actor actor) {
        permissions.require(actor, "je.view", tenantId);
        var entries = entryRepository.findByTenantIdOrderByPostingDateDescCreatedAtDesc(tenantId);
        if (statuses == null || statuses.isEmpty()) return entries.stream().map(this::viewOf).toList();
        return entries.stream().filter(e -> statuses.contains(e.status)).map(this::viewOf).toList();
    }

    public JournalDtos.EntryView get(UUID tenantId, UUID entryId, Actor actor) {
        permissions.require(actor, "je.view", tenantId);
        return viewOf(entryRepository.findByIdAndTenantId(entryId, tenantId)
            .orElseThrow(() -> ApiException.notFound("JE_NOT_FOUND", "Journal entry not found")));
    }

    /** Uncertain postings queue (operator surface, ui-std §5.1 / §10.3). */
    public List<JournalDtos.EntryView> uncertain(UUID tenantId, Actor actor) {
        permissions.require(actor, "je.view", tenantId);
        return entryRepository.findByTenantIdAndStatus(tenantId, JournalEntry.Status.COMMIT_UNKNOWN)
            .stream().map(this::viewOf).toList();
    }

    // ---------------- internals ----------------

    private record ParsedLine(UUID glAccountId, String direction, long amount, String memo) {}

    /** boolean[] has no Arrays.stream overload — explicit scans. */
    private static boolean allSet(boolean[] flags) {
        for (boolean b : flags) if (!b) return false;
        return true;
    }

    private static boolean noneSet(boolean[] flags) {
        for (boolean b : flags) if (b) return false;
        return true;
    }

    private List<ParsedLine> parseLines(List<JournalDtos.LineRequest> requests, com.fsp.core.ledger.Book book) {
        var gls = glAccountRepository.findAllById(requests.stream()
            .map(JournalDtos.LineRequest::glAccountId).filter(java.util.Objects::nonNull).distinct().toList());
        var glById = gls.stream().collect(java.util.stream.Collectors.toMap(g -> g.id, g -> g));

        List<ParsedLine> parsed = new ArrayList<>();
        for (JournalDtos.LineRequest lr : requests) {
            String direction = switch (lr.direction() == null ? "" : lr.direction().toUpperCase()) {
                case "DEBIT" -> "DEBIT";
                case "CREDIT" -> "CREDIT";
                default -> throw ApiException.badRequest("JE_DIRECTION", "direction must be DEBIT or CREDIT");
            };
            long amount;
            try {
                amount = Long.parseLong(lr.amount() == null ? "" : lr.amount().trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("JE_AMOUNT",
                    "amount must be a positive minor-unit integer string (e.g. \"125000\")");
            }
            if (amount <= 0 || amount > 4_000_000_000_000L) {
                throw ApiException.badRequest("JE_AMOUNT", "amount out of range for a posted JE leg");
            }
            var gl = glById.get(lr.glAccountId());
            if (gl == null) {
                throw ApiException.badRequest("JE_ACCOUNT_UNKNOWN", "Unknown GL account: " + lr.glAccountId());
            }
            if (!gl.bookId.equals(book.id) || !gl.tenantId.equals(book.tenantId)) {
                throw ApiException.forbidden("TENANT_MISMATCH", "GL account out of scope for this book");
            }
            if (!gl.currency.equals(book.currency)) {
                throw ApiException.conflict("JE_CURRENCY_MISMATCH",
                    "GL account currency %s != book currency %s".formatted(gl.currency, book.currency));
            }
            if (!"ACTIVE".equals(gl.status) || !gl.postingAllowed) {
                throw ApiException.conflict("JE_ACCOUNT_NOT_POSTABLE",
                    "GL account %d is not postable (status=%s)".formatted(gl.code, gl.status));
            }
            parsed.add(new ParsedLine(lr.glAccountId(), direction, amount, lr.memo()));
        }
        return parsed;
    }

    private List<PostingPlanner.LineSpec> specs(List<JournalLine> lines) {
        return lines.stream()
            .map(l -> new PostingPlanner.LineSpec(l.lineNo, l.glAccountId, l.direction, l.amount.longValueExact()))
            .toList();
    }

    private PostingOutcome dispatch(com.fsp.core.ledger.Book book, JournalEntry entry,
                                    PostingPlanner.Plan plan, List<JournalLine> lines) {
        var tbByGl = new java.util.HashMap<UUID, UUID>();
        for (var gl : glAccountRepository.findAllById(lines.stream().map(l -> l.glAccountId).distinct().toList())) {
            if (gl.tbAccountId == null) {
                throw ApiException.conflict("GL_NOT_PROVISIONED",
                    "GL account %d has no TigerBeetle account".formatted(gl.code));
            }
            tbByGl.put(gl.id, UUID.fromString(gl.tbAccountId));
        }

        List<TbService.TransferSpec> transferSpecs = new ArrayList<>();
        for (PostingPlanner.PlannedLeg leg : plan.legs()) {
            transferSpecs.add(new TbService.TransferSpec(
                leg.transferId(),
                tbByGl.get(leg.debitAccountId()),
                tbByGl.get(leg.creditAccountId()),
                leg.amount(),
                book.ledgerId,
                1,
                tbService.transferFlagLinked(leg.linked()),
                entry.id,                                  // user_data_128 = entry (CDC correlation)
                entry.tenantId.getLeastSignificantBits(),  // user_data_64 = tenant hash
                0));
        }

        TbResult result = tbService.createTransfers(transferSpecs);
        if (props.getJournal().isForceUnknown()) {
            // Recovery harness knob: pretend the response was lost even on success (acceptance #21)
            log.warn("je_force_unknown_knob entryId={} — TEST hook active, reporting Unknown despite TB result {}",
                entry.id, result.getClass().getSimpleName());
            return new PostingOutcome.Unknown("test-injected: response lost after dispatch");
        }
        return switch (result) {
            case TbResult.Ok ok -> new PostingOutcome.Committed(plan.planHash(),
                plan.legs().stream().map(PostingPlanner.PlannedLeg::transferId).toList());
            case TbResult.DefinitiveRejection rejected -> new PostingOutcome.Rejected(rejected.detail());
            case TbResult.UnknownTransport unknown -> new PostingOutcome.Unknown(unknown.detail());
        };
    }

    private JournalTx.Snapshot txSnapshot(JournalEntry je) {
        // only used pre-READY (no plan yet)
        return new JournalTx.Snapshot(je.id, je.tenantId, je.bookId, je.currency, je.status,
            je.planHash, List.of(), je.makerId, je.checkerId, je.finalizedAt, je.version);
    }

    private JournalDtos.EntryView viewOf(JournalEntry je) {
        return view(je, lineRepository.findByEntryIdOrderByLineNoAsc(je.id));
    }

    private JournalDtos.EntryView viewOf(JournalTx.Snapshot s) {
        JournalEntry je = entryRepository.findById(s.entryId())
            .orElseThrow(() -> ApiException.notFound("JE_NOT_FOUND", "Journal entry vanished"));
        return view(je, lineRepository.findByEntryIdOrderByLineNoAsc(je.id));
    }

    private JournalDtos.EntryView view(JournalEntry je, List<JournalLine> lines) {
        List<JournalDtos.LineView> lineViews = lines.stream()
            .map(l -> new JournalDtos.LineView(l.lineNo, l.glAccountId, l.direction,
                l.amount.toPlainString(), l.memo)).toList();
        List<String> ids = (je.tbTransferIds == null || je.tbTransferIds.isBlank())
            ? List.of()
            : Arrays.stream(je.tbTransferIds.split(",")).filter(s -> !s.isBlank()).toList();
        return new JournalDtos.EntryView(je.id, je.tenantId, je.bookId, je.currency, je.entryType,
            je.postingDate, je.valueDate, je.description, je.reference,
            je.totalDebits.toPlainString(), je.totalCredits.toPlainString(),
            je.status, je.planHash, ids, je.makerId, je.checkerId, je.finalizedAt, je.version, lineViews, null);
    }
}
