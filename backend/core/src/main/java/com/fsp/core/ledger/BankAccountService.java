package com.fsp.core.ledger;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.KeyLocks;
import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Settlement bank accounts (F-10). The organization's designated persons file them
 * (bank.account.create); a checker of the SAME organization approves them
 * (bank.account.approve) — four-eyes at person_id through the standard approval lane.
 * The platform root's own account is approved by the platform team's checker, which is
 * the same machinery at tenant = platform root (PLATFORM_ADMIN holds both planes).
 *
 * Concurrency (AGENTS §2d): the terminal transition is a status precondition under an
 * optimistic-locked save; the approve path additionally serializes
 * activate-of-this-account + demote-of-the-previous-holder through KeyLocks keyed by
 * aggregate id so two checkers racing cannot double-activate (the second one loses as
 * its precondition read is stale).
 */
@Slf4j
@Service
public class BankAccountService {

    /** Namibian settlement banks (BoN registry) — Phase-0 static list; master data lives in the KYC plugin. */
    public static final Map<String, String> BANKS = Map.of(
        "BANK_WINDHOEK", "Bank Windhoek",
        "FNB_NAMIBIA", "First National Bank Namibia",
        "NEDBANK_NAMIBIA", "Nedbank Namibia",
        "STANDARD_BANK", "Standard Bank Namibia",
        "BANK_BIC", "Bank BIC Namibia",
        "LETSHEGO", "Letshego Bank Namibia",
        "BANCO_ATLANTICO", "Banco Atlántico (foreign)");

    private static final Set<String> ACCOUNT_TYPES =
        Set.of(BankAccount.SETTLEMENT, BankAccount.CURRENT, BankAccount.SAVINGS);

    private final BankAccountRepository rows;
    private final PersonRepository persons;
    private final PermissionService permissions;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;
    private final KeyLocks locks;

    public BankAccountService(BankAccountRepository rows, PersonRepository persons,
                              PermissionService permissions, ApprovalService approvals,
                              AuditService audit, OutboxService outbox, KeyLocks locks) {
        this.rows = rows;
        this.persons = persons;
        this.permissions = permissions;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
        this.locks = locks;
    }

    public BankAccounts.BankAccountListResponse list(UUID tenantId, Actor actor) {
        permissions.requireAny(actor, List.of("bank.account.view"), tenantId);
        List<BankAccounts.BankAccountView> items = rows.findByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream().map(this::toView).toList();
        return new BankAccounts.BankAccountListResponse(items);
    }

    /** Maker side: file the organization's settlement account and open its four-eyes approval. */
    @Transactional
    public BankAccounts.BankAccountView create(UUID tenantId, Actor maker, BankAccounts.NewBankAccount in) {
        permissions.require(maker, "bank.account.create", tenantId);
        validate(tenantId, in);
        if (maker.personId() == null || maker.type() == Actor.ActorType.MACHINE) {
            throw ApiException.forbidden("MACHINE_MAKER", "Four-eyes is person-anchored; a machine actor cannot file a bank account");
        }

        BankAccount row = new BankAccount();
        row.id = UUID.randomUUID();
        row.tenantId = tenantId;
        row.holderName = in.holderName().trim();
        row.bankCode = in.bankCode().trim();
        row.branchCode = in.branchCode().trim();
        row.accountNumber = in.accountNumber().trim();
        row.accountType = in.accountType().trim();
        row.currency = in.currency().trim().toUpperCase(Locale.ROOT);
        row.status = BankAccount.Status.PENDING;
        row.makerPersonId = maker.personId();
        rows.save(row);

        String payload = payloadJson(row);
        ApprovalRequest approval = approvals.open(tenantId, ApprovalRequest.OpType.BANK_ACCOUNT_CREATE,
            row.id, maker, "bank-account:" + row.bankCode + ":" + mask(row.accountNumber), payload);
        // reopen of a REJECTED/REPLACED account is a NEW row by design (immutable history).
        row.approvalId = approval.id;
        rows.save(row);

        audit.record(maker.personId(), null, tenantId, null,
            "BANK_ACCOUNT_REQUESTED", "BankAccount", row.id.toString(),
            null, audit.hashState(mask(row.accountNumber)), false);
        log.info("bank_account_requested tenant={} bank={} branch={} approvalId={} by={}",
            tenantId, row.bankCode, row.branchCode, approval.id, maker.personId());
        return toView(row);
    }

    // ---------- resolution effects (called by the approval handler, same transaction) ----------

    @Transactional
    public void activate(ApprovalRequest approval, Actor checker) {
        BankAccount row = load(approval.targetResourceId);
        if (!BankAccount.Status.PENDING.equals(row.status)) {
            log.warn("bank_account_transition_conflict id={} from={} tenant={}",
                row.id, row.status, row.tenantId);
            throw ApiException.conflict("BANK_STATE",
                "Bank account %s is %s — only PENDING accounts can be activated".formatted(row.id, row.status));
        }
        // Single-settlement invariant: demote the incumbent inside the same critical section.
        locks.withLock("bank-activation", row.tenantId, () -> {
            rows.demoteActive(row.tenantId);
            row.status = BankAccount.Status.ACTIVE;
            rows.save(row);
            audit.record(checker.personId(), approval.makerId, row.tenantId, null,
                "BANK_ACCOUNT_ACTIVATED", "BankAccount", row.id.toString(),
                audit.hashState("status=PENDING;" + mask(row.accountNumber)),
                audit.hashState("status=ACTIVE;" + mask(row.accountNumber)), false);
            outbox.record("BankAccount", row.id, "BankAccount.Activated",
                String.format("{\"account\":\"%s\",\"tenant\":\"%s\",\"bank\":\"%s\",\"currency\":\"%s\"}",
                    row.id, row.tenantId, row.bankCode, row.currency));
            log.info("bank_account_activated id={} tenant={} bank={} checker={}",
                row.id, row.tenantId, row.bankCode, checker.personId());
            return null;
        });
    }

    @Transactional
    public void rejected(ApprovalRequest approval, Actor checker) {
        BankAccount row = load(approval.targetResourceId);
        if (!BankAccount.Status.PENDING.equals(row.status)) {
            log.warn("bank_account_transition_conflict id={} from={} tenant={}",
                row.id, row.status, row.tenantId);
            throw ApiException.conflict("BANK_STATE",
                "Bank account %s is %s — only PENDING accounts can be rejected".formatted(row.id, row.status));
        }
        row.status = BankAccount.Status.REJECTED;
        rows.save(row);
        audit.record(checker.personId(), approval.makerId, row.tenantId, null,
            "BANK_ACCOUNT_REJECTED", "BankAccount", row.id.toString(),
            audit.hashState("status=PENDING;" + mask(row.accountNumber)),
            audit.hashState("status=REJECTED;" + mask(row.accountNumber)), false);
        log.warn("bank_account_rejected id={} tenant={} checker={}", row.id, row.tenantId, checker.personId());
    }

    // ---------- internals ----------

    private BankAccount load(UUID id) {
        return rows.findById(id).orElseThrow(() ->
            ApiException.notFound("BANK_ACCOUNT_NOT_FOUND", "Bank account %s not found".formatted(id)));
    }

    private void validate(UUID tenantId, BankAccounts.NewBankAccount in) {
        List<String> errors = new ArrayList<>();
        if (in == null) throw ApiException.badRequest("BODY_INVALID", "Request body required");
        if (in.holderName() == null || in.holderName().trim().length() < 2 || in.holderName().trim().length() > 160) {
            errors.add("holderName must be 2-160 characters");
        }
        if (in.bankCode() == null || !BANKS.containsKey(in.bankCode().trim())) {
            errors.add("bankCode must be one of " + BANKS.keySet());
        }
        if (in.branchCode() == null || !in.branchCode().trim().matches("\\d{5,6}")) {
            errors.add("branchCode must be 5-6 digits");
        }
        if (in.accountNumber() == null || !in.accountNumber().trim().matches("\\d{6,16}")) {
            errors.add("accountNumber must be 6-16 digits");
        }
        if (in.accountType() == null || !ACCOUNT_TYPES.contains(in.accountType().trim())) {
            errors.add("accountType must be SETTLEMENT, CURRENT or SAVINGS");
        }
        if (in.currency() == null || !in.currency().trim().toUpperCase(Locale.ROOT).matches("[A-Z]{3}")) {
            errors.add("currency must be an ISO-4217 alpha-3 code");
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("BANK_INVALID", String.join("; ", errors));
        }
    }

    private String payloadJson(BankAccount row) {
        return String.format("{\"holderName\":\"%s\",\"bankCode\":\"%s\",\"branchCode\":\"%s\"," +
                "\"accountNumber\":\"%s\",\"accountType\":\"%s\",\"currency\":\"%s\"}",
            row.holderName, row.bankCode, row.branchCode, mask(row.accountNumber), row.accountType, row.currency);
    }

    private String mask(String accountNumber) {
        return accountNumber == null || accountNumber.length() <= 4
            ? "••••" : "••••••" + accountNumber.substring(accountNumber.length() - 4);
    }

    private BankAccounts.BankAccountView toView(BankAccount row) {
        String makerName = persons.findById(row.makerPersonId)
            .map(p -> p.fullName).orElse(null);
        return new BankAccounts.BankAccountView(row.id, row.tenantId, row.holderName, row.bankCode,
            row.branchCode, mask(row.accountNumber), row.accountType, row.currency, row.status,
            row.approvalId, makerName, row.version, row.createdAt, row.updatedAt);
    }
}
