package com.fsp.core.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A payment against an invoice (V41 {@code invoice_payments}). Only SUCCEEDED reduces the
 * balance; PENDING / AWAITING_CONFIRMATION / COMMIT_UNKNOWN are "in flight" and reserve their
 * amount so two payments can never together exceed the balance.
 */
@Entity
@Table(name = "invoice_payments")
public class InvoicePayment {

    public static final class Method {
        public static final String WALLET = "WALLET";
        public static final String BANK_TRANSFER = "BANK_TRANSFER";
        public static final String CARD = "CARD";
        public static final List<String> ALL = List.of(WALLET, BANK_TRANSFER, CARD);

        private Method() {}
    }

    public static final class Status {
        public static final String PENDING = "PENDING";
        public static final String AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION";
        public static final String SUCCEEDED = "SUCCEEDED";
        public static final String FAILED = "FAILED";
        public static final String COMMIT_UNKNOWN = "COMMIT_UNKNOWN";
        public static final List<String> IN_FLIGHT = List.of(PENDING, AWAITING_CONFIRMATION, COMMIT_UNKNOWN);

        private Status() {}
    }

    @Id
    public UUID id;
    @Column(name = "invoice_id", nullable = false)
    public UUID invoiceId;
    @Column(nullable = false)
    public String method;
    @Column(name = "amount_minor", nullable = false)
    public long amountMinor;
    @Column(nullable = false)
    public String status;
    public String reference;
    @Column(name = "rail_ref")
    public String railRef;
    @Column(name = "failure_code")
    public String failureCode;
    @Column(name = "payer_person_id", nullable = false)
    public UUID payerPersonId;
    @Column(name = "confirmed_by")
    public UUID confirmedBy;

    @Version
    public long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;
    @Column(name = "confirmed_at")
    public Instant confirmedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
