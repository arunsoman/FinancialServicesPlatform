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
 * Customer e-money wallet (V1 `wallet`). TigerBeetle (book 806) is the authoritative
 * balance record — this row is the identity/state/limit anchor. Balance = credits − debits.
 */
@Entity
@Table(name = "wallet")
public class Wallet {

    public static final class Status {
        public static final String ACTIVE = "ACTIVE";
        public static final String FROZEN = "FROZEN";
        public static final String CLOSED = "CLOSED";

        private Status() {}
    }

    @Id
    public UUID id;
    @Column(name = "wallet_ref", nullable = false, unique = true)
    public String walletRef;
    @Column(name = "person_id", nullable = false)
    public UUID personId;
    /** PSD-3 §9.1.1: regulated e-money balances are NAD-only (DB CHECK on CHAR(3)). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, columnDefinition = "CHAR(3)")
    public String currency;
    @Column(name = "status", nullable = false)
    public String status;
    @Column(name = "daily_limit_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal dailyLimitMinor;
    @Column(name = "tb_account_id", nullable = false)
    public UUID tbAccountId;
    @Column(name = "opened_at", nullable = false)
    public Instant openedAt;
    @Version
    public long version;

    public long dailyLimit() { return dailyLimitMinor.longValueExact(); }
}
