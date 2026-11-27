package com.fsp.core.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Settlement bank account of one tenant (the organization's payout destination).
 *
 * State machine (guarded by status preconditions + optimistic lock, AGENTS §2d):
 *   PENDING → ACTIVE (checker approve) | REJECTED (checker reject)
 *   ACTIVE  → REPLACED (a newer account is activated)
 *
 * One ACTIVE account per tenant — enforced in the service AND by the partial unique
 * index {@code ux_bank_accounts_one_active}. Money still never moves on this row alone:
 * it only names the destination; JEs/TB remain the only money path (AGENTS §3.5).
 */
@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    public static final class Status {
        public static final String PENDING = "PENDING";
        public static final String ACTIVE = "ACTIVE";
        public static final String REPLACED = "REPLACED";
        public static final String REJECTED = "REJECTED";

        private Status() {}
    }

    public static final String SETTLEMENT = "SETTLEMENT";
    public static final String CURRENT = "CURRENT";
    public static final String SAVINGS = "SAVINGS";

    @Id
    public UUID id;
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;
    @Column(name = "holder_name", nullable = false)
    public String holderName;
    @Column(name = "bank_code", nullable = false)
    public String bankCode;
    @Column(name = "branch_code", nullable = false)
    public String branchCode;
    @Column(name = "account_number", nullable = false)
    public String accountNumber;
    @Column(name = "account_type", nullable = false)
    public String accountType;
    @Column(nullable = false)
    public String currency;
    @Column(nullable = false)
    public String status = Status.PENDING;
    @Column(name = "approval_id")
    public UUID approvalId;
    @Column(name = "maker_person_id", nullable = false)
    public UUID makerPersonId;

    @Version
    public long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    public Instant createdAt;
    @Column(name = "updated_at", insertable = false, updatable = false)
    public Instant updatedAt;
}
