package com.fsp.psp.settlement;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Nominal clearing position for one (connector, currency, day) — V1 table. */
@Entity
@Table(name = "clearing_position")
public class ClearingPosition {

    @Id
    public UUID id;

    @Column(name = "connector_code", nullable = false)
    public String connectorCode;

    @Column(nullable = false, columnDefinition = "CHAR(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    public String currency;

    @Column(name = "position_date", nullable = false)
    public java.time.LocalDate positionDate;

    /** TB clearing account id on the PSP book — must balance to amountMinor. */
    @Column(name = "tb_account_id", nullable = false)
    public UUID tbAccountId;

    /** Sum of net capture amounts parked in clearing for this cycle (string on the wire §2b). */
    @Column(name = "amount_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal amountMinor;

    @Column(nullable = false)
    public boolean settled = false;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(nullable = false)
    public long version;
}
