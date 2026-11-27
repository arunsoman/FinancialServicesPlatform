package com.fsp.core.ledger;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.KeyLocks;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-10 domain rules: validation, state-machine preconditions under optimistic locking,
 * single-settlement demotion inside the KeyLocks critical section (AGENTS §2d).
 */
class BankAccountServiceTest {

    private BankAccountRepository rows;
    private PersonRepository persons;
    private PermissionService permissions;
    private ApprovalService approvals;
    private AuditService audit;
    private OutboxService outbox;
    private KeyLocks locks;
    private BankAccountService service;

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final Actor MAKER =
        new Actor(UUID.randomUUID(), "merchant-admin", TENANT, Actor.ActorType.PERSON, null);
    private static final Actor CHECKER =
        new Actor(UUID.randomUUID(), "merchant-checker", TENANT, Actor.ActorType.PERSON, null);

    @BeforeEach
    void setUp() {
        rows = Mockito.mock(BankAccountRepository.class);
        persons = Mockito.mock(PersonRepository.class);
        permissions = Mockito.mock(PermissionService.class);
        approvals = Mockito.mock(ApprovalService.class);
        audit = Mockito.mock(AuditService.class);
        outbox = Mockito.mock(OutboxService.class);
        locks = Mockito.mock(KeyLocks.class);
        // run the lock supplier inline under the mock (single-node test = no real contention)
        org.mockito.stubbing.Answer<Object> inline = inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> s = (java.util.function.Supplier<Object>) inv.getArgument(2);
            return s.get();
        };
        when(locks.withLock(any(), any(), Mockito.<java.util.function.Supplier<Object>>any())).thenAnswer(inline);
        service = new BankAccountService(rows, persons, permissions, approvals, audit, outbox, locks);
    }

    @Test
    void createValidatesNamibianShapeAndOpensApproval() {
        BankAccounts.NewBankAccount bad = new BankAccounts.NewBankAccount(
            "Namib Fresh Produce", "BANK_WINDHOEK", "481", "12345", "SETTLEMENT", "NAD");
        ApiException e = catchThrowableOfType(() -> service.create(TENANT, MAKER, bad), ApiException.class);
        assertThat(e).isNotNull();
        assertThat(e.getMessage()).contains("branchCode must be 5-6 digits");
        verify(rows, never()).save(any());

        BankAccounts.NewBankAccount good = new BankAccounts.NewBankAccount(
            "Namib Fresh Produce", "BANK_WINDHOEK", "481972", "12345678", "SETTLEMENT", "NAD");
        UUID accountId = UUID.randomUUID();
        when(rows.save(any())).thenAnswer(inv -> {
            BankAccount a = inv.getArgument(0);
            a.id = a.id == null ? accountId : a.id;
            return a;
        });
        when(approvals.open(Mockito.eq(TENANT), Mockito.eq(ApprovalRequest.OpType.BANK_ACCOUNT_CREATE),
            any(), Mockito.eq(MAKER), Mockito.anyString(), Mockito.anyString()))
            .thenAnswer(inv -> approval(inv.getArgument(2)));

        BankAccounts.BankAccountView view = service.create(TENANT, MAKER, good);

        assertThat(view.status()).isEqualTo(BankAccount.Status.PENDING);
        assertThat(view.maskedAccountNumber()).endsWith("5678").doesNotContain("12345678");
        verify(approvals).open(Mockito.eq(TENANT), Mockito.eq(ApprovalRequest.OpType.BANK_ACCOUNT_CREATE),
            any(), Mockito.eq(MAKER), Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void activateFlipsPendingToActiveAndDemotesIncumbent() {
        ApprovalRequest approval = approval(UUID.randomUUID());
        BankAccount row = row(BankAccount.Status.PENDING, approval.targetResourceId);
        when(rows.findById(row.id)).thenReturn(Optional.of(row));

        service.activate(approval, CHECKER);

        verify(locks).withLock(Mockito.eq("bank-activation"), Mockito.eq(TENANT), Mockito.<java.util.function.Supplier<Object>>any());
        verify(rows).demoteActive(TENANT);
        assertThat(row.status).isEqualTo(BankAccount.Status.ACTIVE);
    }

    @Test
    void activateRefusesWrongState() {
        ApprovalRequest approval = approval(UUID.randomUUID());
        BankAccount row = row(BankAccount.Status.ACTIVE, approval.targetResourceId);
        when(rows.findById(row.id)).thenReturn(Optional.of(row));

        ApiException e = catchThrowableOfType(() -> service.activate(approval, CHECKER), ApiException.class);
        assertThat(e).isNotNull();
        assertThat(e.getMessage()).contains("only PENDING");
        verify(rows, never()).demoteActive(any());
        verify(locks, never()).withLock(any(), any(), Mockito.<java.util.function.Supplier<Object>>any());
    }

    @Test
    void rejectFlipsPendingToRejected() {
        ApprovalRequest approval = approval(UUID.randomUUID());
        BankAccount row = row(BankAccount.Status.PENDING, approval.targetResourceId);
        when(rows.findById(row.id)).thenReturn(Optional.of(row));

        service.rejected(approval, CHECKER);

        assertThat(row.status).isEqualTo(BankAccount.Status.REJECTED);
        verify(rows).save(row);
        verify(locks, never()).withLock(any(), any(), Mockito.<java.util.function.Supplier<Object>>any());
    }

    // ---------- helpers ----------

    private static ApprovalRequest approval(UUID targetId) {
        ApprovalRequest a = new ApprovalRequest();
        a.id = UUID.randomUUID();
        a.tenantId = TENANT;
        a.operationType = ApprovalRequest.OpType.BANK_ACCOUNT_CREATE;
        a.targetResourceId = targetId;
        a.makerId = MAKER.personId();
        a.status = ApprovalRequest.Status.PENDING_CHECKER;
        return a;
    }

    private static BankAccount row(String status, UUID id) {
        BankAccount a = new BankAccount();
        a.id = id;
        a.tenantId = TENANT;
        a.holderName = "Namib Fresh Produce";
        a.bankCode = "BANK_WINDHOEK";
        a.branchCode = "481972";
        a.accountNumber = "12345678";
        a.accountType = BankAccount.SETTLEMENT;
        a.currency = "NAD";
        a.status = status;
        a.makerPersonId = MAKER.personId();
        return a;
    }
}
