package com.fsp.core.ledger;

import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.RequestCorrelation;
import com.fsp.core.common.Uuid5;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LedgerService {

    private final BookRepository bookRepository;
    private final GlAccountRepository glAccountRepository;
    private final AccountingPeriodRepository periodRepository;
    private final TbService tbService;
    private final PermissionService permissions;
    private final TenantRepository tenantRepository;
    private final AuditService audit;
    private final OutboxService outbox;
    private final TransactionTemplate tx;

    public LedgerService(BookRepository bookRepository,
                         GlAccountRepository glAccountRepository,
                         AccountingPeriodRepository periodRepository,
                         TbService tbService, PermissionService permissions,
                         TenantRepository tenantRepository, AuditService audit, OutboxService outbox,
                         TransactionTemplate tx) {
        this.bookRepository = bookRepository;
        this.glAccountRepository = glAccountRepository;
        this.periodRepository = periodRepository;
        this.tbService = tbService;
        this.permissions = permissions;
        this.tenantRepository = tenantRepository;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
    }

    /** Provision the default CoA template for the tenant's sub-type (PRD §6 endpoint). */
    @Transactional
    public List<GlAccount> provisionDefault(UUID tenantId, UUID bookId, Actor actor) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
        String template = tenant.subType == null || tenant.subType.isBlank() ? "GENERIC" : tenant.subType;
        return provisionTemplate(tenantId, bookId, actor, CoaTemplates.forTenant(template), template);
    }

    /** ISO-4217 numeric ledger id (bounded set for Phase-0, per PRD §3.5). */
    public static int isoNumeric(String currency) {
        return switch (currency) {
            case "NAD" -> 516;
            case "ZAR" -> 710;
            case "USD" -> 840;
            case "EUR" -> 978;
            case "GBP" -> 826;
            default -> throw ApiException.badRequest("CURRENCY_UNSUPPORTED",
                "Phase-0 supports NAD/ZAR/USD/EUR/GBP; add ISO numeric for " + currency);
        };
    }

    // ---------- books ----------

    @Transactional
    public Book createBook(UUID tenantId, String name, String currency) {
        if (currency == null || currency.length() != 3 || !currency.chars().allMatch(Character::isLetter)) {
            throw ApiException.badRequest("BOOK_CURRENCY_INVALID", "currency must be a 3-letter ISO code");
        }
        Book book = new Book();
        book.id = UUID.randomUUID();
        book.tenantId = tenantId;
        book.name = name;
        book.currency = currency.toUpperCase();
        book.ledgerId = isoNumeric(book.currency);
        book.status = "SUSPENDED";
        Book saved = bookRepository.save(book);
        log.debug("book_created tenantId={} bookId={} currency={}", tenantId, saved.id, book.currency);
        var actor = RequestCorrelation.currentActor();
        audit.record(actor == null ? null : actor.personId(), null, tenantId, saved.id,
            "BOOK_CREATED", "BOOK", saved.id.toString(),
            audit.hashState("null"), audit.hashState("status=SUSPENDED;currency=" + book.currency), false);
        return saved;
    }

    public List<Book> books(UUID tenantId, Actor actor) {
        permissions.require(actor, "gl.account.view", tenantId);
        return bookRepository.findByTenantIdOrderByCreatedAtAsc(tenantId);
    }

    /** KYC verification opened the tenant: its SUSPENDED books go ACTIVE (docs §7.3,
     *  "child tenant is born with suspended books; the book auto-opens on verdict"). */
    @Transactional
    public void openBooksForTenant(UUID tenantId) {
        List<Book> opened = bookRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
            .filter(b -> "SUSPENDED".equals(b.status))
            .toList();
        if (opened.isEmpty()) return;
        var actor = RequestCorrelation.currentActor();
        for (Book b : opened) {
            b.status = "ACTIVE";
            bookRepository.save(b);
            audit.record(actor == null ? null : actor.personId(), null, tenantId, b.id,
                "BOOK_OPENED", "BOOK", b.id.toString(),
                null, audit.hashState("status=ACTIVE;currency=" + b.currency), false);
        }
        log.info("books_opened tenantId={} books={}", tenantId, opened.size());
    }

    // ---------- CoA provisioning ----------

    /**
     * Provision a CoA template into a book: GL rows + TigerBeetle accounts, then book -> ACTIVE
     * + an OPEN current period (PRD actor table: bootstrap self-approval audited).
     * Three phases (AGENTS.md §2d — a DB transaction must never span remote I/O):
     *   1. read + validate + derive deterministic rows (each read gets its own connection),
     *   2. TigerBeetle createAccounts OUTSIDE any transaction (deterministic UUIDs make it
     *      idempotent — a retry after UnknownTransport re-dispatches the same ids safely),
     *   3. short DB tx: race-guarded persist of GL rows (sorted id order), book flip, period,
     *      audit + outbox — atomically, and only after a confirmed TbResult.Ok, so GL rows
     *      never reference accounts TB rejected.
     */
    public List<GlAccount> provisionTemplate(UUID tenantId, UUID bookId, Actor actor,
                                             List<CoaTemplates.Row> rows, String templateName) {
        Book book = requireBook(tenantId, bookId);
        if (!glAccountRepository.findByBookIdOrderByCodeAsc(bookId).isEmpty()) {
            throw ApiException.conflict("COA_ALREADY_PROVISIONED", "Book already has GL accounts; provision once");
        }

        List<TbService.AccountSpec> tbSpecs = new ArrayList<>();
        List<GlAccount> created = deriveGlRows(tenantId, book, rows, actor, tbSpecs);

        switch (tbService.createAccounts(tbSpecs)) {
            case TbResult.Ok ok -> log.debug("tb_accounts_provisioned bookId={} count={}", bookId, tbSpecs.size());
            case TbResult.DefinitiveRejection rejected -> {
                log.warn("coa_provision_rejected tenantId={} bookId={} detail={}", tenantId, bookId, rejected.detail());
                throw ApiException.conflict("TB_ACCOUNT_REJECTED", rejected.detail());
            }
            case TbResult.UnknownTransport unknown -> {
                log.error("coa_provision_unknown_transport tenantId={} bookId={} detail={}", tenantId, bookId, unknown.detail());
                throw ApiException.serviceUnavailable("LEDGER_UNAVAILABLE", unknown.detail());
            }
        }

        tx.executeWithoutResult(status -> persistProvisioned(tenantId, book, created, templateName, actor));
        log.info("coa_provisioned tenantId={} bookId={} currency={} accounts={} template={} (bootstrap self-approval audited)",
            tenantId, bookId, book.currency, created.size(), templateName);
        return created;
    }

    /** Short DB-tx phase of {@link #provisionTemplate}: race guard + persist + activate + audit. */
    private void persistProvisioned(UUID tenantId, Book book, List<GlAccount> created,
                                    String templateName, Actor actor) {
        var alreadyPersisted = glAccountRepository.findByBookIdOrderByCodeAsc(book.id).stream()
            .map(g -> g.id).collect(Collectors.toSet());
        List<GlAccount> fresh = created.stream()
            .filter(g -> !alreadyPersisted.contains(g.id))
            .sorted(Comparator.comparing(g -> g.id))
            .toList();
        if (fresh.isEmpty()) {
            log.debug("coa_provision_raced bookId={} rows already persisted by concurrent provisioner", book.id);
        } else {
            glAccountRepository.saveAll(fresh); // deterministic row order (AGENTS.md §2d.1.2)
        }

        boolean activated = false;
        if ("SUSPENDED".equals(book.status)) { // first activation only — the period is created once
            book.status = "ACTIVE";
            bookRepository.save(book);
            activated = true;
        }
        if (activated) {
            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            AccountingPeriod period = new AccountingPeriod();
            period.id = UUID.randomUUID();
            period.tenantId = tenantId;
            period.bookId = book.id;
            period.fiscalYear = today.getYear();
            period.periodNo = today.getMonthValue();
            period.startDate = today.withDayOfMonth(1);
            period.endDate = today.withDayOfMonth(today.lengthOfMonth());
            period.status = "OPEN";
            period.openedBy = actor.personId();
            periodRepository.save(period);
        }

        audit.record(actor.personId(), null, tenantId, book.id, "COA_PROVISIONED", "BOOK", book.id.toString(),
            audit.hashState("status=SUSPENDED"), audit.hashState("accounts=%d;template=%s".formatted(created.size(), templateName)),
            false);
        outbox.record("Book", book.id, "GL_ACCOUNT_CREATED",
            "{\"bookId\":\"%s\",\"template\":\"%s\",\"accounts\":%d}".formatted(book.id, templateName, created.size()));
    }

    /**
     * Additive, idempotent CoA provision (plans/inventory-plugin-plan.md D4): inserts ONLY rows whose
     * code does not already exist in the book; existing accounts at those codes are used as-is
     * (code collisions, e.g. PSP template 2110 "Payouts in Transit", are never recreated).
     * Does NOT flip the book status and does NOT create periods. Rows carry the same deterministic
     * UUID math as provisionTemplate, so a duplicate ensure is idempotent (already-persisted rows
     * are skipped, never a duplicate PK error). Same three phases as provisionTemplate (AGENTS.md
     * §2d): TigerBeetle createAccounts runs OUTSIDE any DB transaction; the persist phase re-checks
     * under a short tx. The plugin's readiness step calls this at activation.
     */
    public List<GlAccount> ensureAdditive(UUID tenantId, UUID bookId, Actor actor, List<CoaTemplates.Row> rows) {
        permissions.require(actor, "gl.account.create", tenantId);
        Book book = requireBook(tenantId, bookId);
        var existingCodes = glAccountRepository.findByBookIdOrderByCodeAsc(bookId).stream()
            .map(g -> g.code).collect(Collectors.toSet());
        List<CoaTemplates.Row> missing = rows.stream().filter(r -> !existingCodes.contains(r.code())).toList();
        if (missing.isEmpty()) {
            log.debug("coa_ensure_noop tenantId={} bookId={}", tenantId, bookId);
            return List.of();
        }

        List<TbService.AccountSpec> tbSpecs = new ArrayList<>();
        List<GlAccount> created = deriveGlRows(tenantId, book, missing, actor, tbSpecs);

        switch (tbService.createAccounts(tbSpecs)) {
            case TbResult.Ok ok -> log.debug("tb_accounts_ensured bookId={} count={}", bookId, tbSpecs.size());
            case TbResult.DefinitiveRejection rejected -> {
                log.warn("coa_ensure_rejected tenantId={} bookId={} detail={}", tenantId, bookId, rejected.detail());
                throw ApiException.conflict("TB_ACCOUNT_REJECTED", rejected.detail());
            }
            case TbResult.UnknownTransport unknown -> {
                log.error("coa_ensure_unknown_transport tenantId={} bookId={} detail={}", tenantId, bookId, unknown.detail());
                throw ApiException.serviceUnavailable("LEDGER_UNAVAILABLE", unknown.detail());
            }
        }

        List<GlAccount> persisted = tx.execute(status -> persistEnsured(tenantId, book, created, actor));
        return persisted;
    }

    /** Short DB-tx phase of {@link #ensureAdditive}: race guard + persist + audit + outbox. */
    private List<GlAccount> persistEnsured(UUID tenantId, Book book, List<GlAccount> created, Actor actor) {
        var existingCodes = glAccountRepository.findByBookIdOrderByCodeAsc(book.id).stream()
            .map(g -> g.code).collect(Collectors.toSet());
        List<GlAccount> fresh = created.stream()
            .filter(g -> !existingCodes.contains(g.code))
            .sorted(Comparator.comparing(g -> g.id))
            .toList();
        if (fresh.isEmpty()) {
            log.debug("coa_ensure_raced bookId={} rows already persisted by concurrent ensure", book.id);
            return List.of();
        }
        glAccountRepository.saveAll(fresh); // deterministic row order (AGENTS.md §2d.1.2)
        audit.record(actor.personId(), null, tenantId, book.id, "COA_ENSURED", "BOOK", book.id.toString(),
            audit.hashState("null"), audit.hashState("accounts=%d;codes=%s".formatted(fresh.size(),
                fresh.stream().map(g -> g.code).map(String::valueOf).sorted().toList())), false);
        outbox.record("Book", book.id, "GL_ACCOUNT_CREATED",
            "{\"bookId\":\"%s\",\"ensure\":\"additive\",\"accounts\":%d}".formatted(book.id, fresh.size()));
        log.info("coa_ensured tenantId={} bookId={} added={} codes={}", tenantId, book.id, fresh.size(),
            fresh.stream().map(g -> g.code).toList());
        return fresh;
    }

    /** Loaded book + tenant-subtree ownership guard, shared by the CoA provisioning phases. */
    private Book requireBook(UUID tenantId, UUID bookId) {
        Book book = bookRepository.findById(bookId)
            .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book " + bookId + " not found"));
        if (!book.tenantId.equals(tenantId)) {
            throw ApiException.forbidden("TENANT_MISMATCH", "Book does not belong to tenant");
        }
        return book;
    }

    /**
     * Deterministic GL row derivation (UUIDv5 per book+code — same inputs always yield the same
     * row and TB account, which is what makes the out-of-transaction dispatch retry-safe)
     * plus the matching TigerBeetle account specs appended to {@code tbSpecsOut}.
     */
    private List<GlAccount> deriveGlRows(UUID tenantId, Book book, List<CoaTemplates.Row> rows, Actor actor,
                                         List<TbService.AccountSpec> tbSpecsOut) {
        List<GlAccount> created = new ArrayList<>();
        for (CoaTemplates.Row row : rows) {
            GlAccount gl = new GlAccount();
            gl.id = Uuid5.glAccountId(book.id, UUID.nameUUIDFromBytes(String.valueOf(row.code()).getBytes()));
            gl.bookId = book.id;
            gl.tenantId = tenantId;
            gl.tbAccountId = Uuid5.glAccountId(book.id, gl.id).toString();
            gl.ledgerId = book.ledgerId;
            gl.code = row.code();
            gl.name = row.name();
            gl.accountType = row.accountType();
            gl.normalBalance = row.normalBalance();
            gl.balanceBound = row.balanceBound();
            gl.postingAllowed = true;
            gl.currency = book.currency;
            gl.status = "ACTIVE";
            gl.createdBy = actor.personId();
            gl.approvedBy = actor.personId(); // machine provisioning, audited
            created.add(gl);
            tbSpecsOut.add(new TbService.AccountSpec(
                Uuid5.glAccountId(book.id, gl.id),
                book.ledgerId,
                row.code(),
                tbService.flagFor(bound(row.balanceBound()), "ASSET".equals(row.accountType())),
                gl.id,
                tenantId.getLeastSignificantBits(),
                0));
        }
        return created;
    }

    private TbService.Bound bound(String balanceBound) {
        return switch (balanceBound) {
            case "DEBIT_BOUND" -> TbService.Bound.DEBIT_BOUND;
            case "CREDIT_BOUND" -> TbService.Bound.CREDIT_BOUND;
            default -> TbService.Bound.NONE;
        };
    }

    // ---------- GL accounts ----------

    public List<GlAccount> glAccounts(UUID tenantId, UUID bookId, Actor actor) {
        permissions.require(actor, "gl.account.view", tenantId);
        Book book = bookRepository.findById(bookId)
            .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        if (!book.tenantId.equals(tenantId)) throw ApiException.forbidden("TENANT_MISMATCH", "Book does not belong to tenant");
        return glAccountRepository.findByBookIdOrderByCodeAsc(bookId);
    }

    public record GlBalanceView(UUID glAccountId, int code, String name, String currency, String status,
                                String tbAccountId, long debitsPosted, long creditsPosted) {}

    public GlBalanceView balance(UUID tenantId, UUID bookId, UUID glAccountId, Actor actor) {
        permissions.require(actor, "gl.account.view", tenantId);
        GlAccount gl = glAccountRepository.findById(glAccountId)
            .orElseThrow(() -> ApiException.notFound("GL_NOT_FOUND", "GL account not found"));
        if (!gl.bookId.equals(bookId) || !gl.tenantId.equals(tenantId)) {
            throw ApiException.forbidden("TENANT_MISMATCH", "Account out of scope");
        }
        long debits = 0, credits = 0;
        if (gl.tbAccountId != null) {
            Optional<TbService.Balance> opt = tbService.lookupBalance(UUID.fromString(gl.tbAccountId));
            if (opt.isPresent()) {
                debits = opt.get().debitsPosted();
                credits = opt.get().creditsPosted();
            }
        }
        return new GlBalanceView(gl.id, gl.code, gl.name, gl.currency, gl.status, gl.tbAccountId, debits, credits);
    }

    // ---------- periods ----------

    public List<AccountingPeriod> periods(UUID tenantId, UUID bookId, Actor actor) {
        permissions.require(actor, "gl.account.view", tenantId);
        return periodRepository.findByBookIdOrderByFiscalYearDescPeriodNoDesc(bookId);
    }

    @Transactional
    public AccountingPeriod openCurrentPeriod(UUID tenantId, UUID bookId, Actor actor) {
        permissions.require(actor, "period.open", tenantId);
        Book book = bookRepository.findById(bookId)
            .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        AccountingPeriod period = new AccountingPeriod();
        period.id = UUID.randomUUID();
        period.tenantId = tenantId;
        period.bookId = bookId;
        period.fiscalYear = today.getYear();
        period.periodNo = today.getMonthValue();
        period.startDate = today.withDayOfMonth(1);
        period.endDate = today.withDayOfMonth(today.lengthOfMonth());
        period.status = "OPEN";
        period.openedBy = actor.personId();
        AccountingPeriod saved = periodRepository.save(period);
        audit.record(actor.personId(), null, tenantId, bookId, "PERIOD_OPENED", "PERIOD", saved.id.toString(),
            audit.hashState("null"), audit.hashState(saved.fiscalYear + "/" + saved.periodNo + "/OPEN"), false);
        return saved;
    }
}
