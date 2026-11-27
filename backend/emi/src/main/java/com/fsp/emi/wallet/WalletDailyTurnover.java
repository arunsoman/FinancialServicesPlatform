package com.fsp.emi.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Daily redemption turnover per wallet (V2 `wallet_daily_turnover`). */
@Entity
@Table(name = "wallet_daily_turnover")
@IdClass(WalletDailyTurnover.Pk.class)
public class WalletDailyTurnover {

    public record Pk(UUID walletId, LocalDate turnDate) implements Serializable {}

    @Id
    @Column(name = "wallet_id", nullable = false)
    public UUID walletId;
    @Id
    @Column(name = "turn_date", nullable = false)
    public LocalDate turnDate;
    @Column(name = "redeemed_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal redeemedMinor;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
