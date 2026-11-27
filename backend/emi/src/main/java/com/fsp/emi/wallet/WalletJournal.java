package com.fsp.emi.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JE metadata for one wallet movement (V2 `wallet_journal`). The TigerBeetle transfer is
 * the money truth; this row is the idempotency anchor — (kind, reference) unique so a
 * statement ref credits a wallet exactly once, and tb_transfer_id is deterministic
 * (Uuid5.journalTransferId) so unknown-transport replays carry the same id.
 */
@Entity
@Table(name = "wallet_journal")
public class WalletJournal {

    public static final class Kind {
        public static final String DEPOSIT = "DEPOSIT";
        public static final String REDEMPTION = "REDEMPTION";
        public static final String ADJUSTMENT = "ADJUSTMENT";
        private Kind() {}
    }

    public static final class State {
        public static final String PENDING = "PENDING";
        public static final String BOOKED = "BOOKED";
        public static final String FAILED = "FAILED";
        private State() {}
    }

    @Id
    public UUID id;
    @Column(name = "wallet_id", nullable = false)
    public UUID walletId;
    @Column(name = "kind", nullable = false, length = 20)
    public String kind;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, columnDefinition = "CHAR(3)")
    public String currency;
    @Column(name = "amount_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal amountMinor;
    @Column(name = "reference")
    public String reference;
    @Column(name = "tb_transfer_id", nullable = false)
    public UUID tbTransferId;
    @Column(name = "state", nullable = false, length = 10)
    public String state;
    @Column(name = "failure_code")
    public String failureCode;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
    @Version
    public long version;
}
