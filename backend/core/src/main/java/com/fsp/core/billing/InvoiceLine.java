package com.fsp.core.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** One line of an invoice (V41 {@code invoice_lines}); immutable once written. */
@Entity
@Table(name = "invoice_lines")
@IdClass(InvoiceLine.Key.class)
public class InvoiceLine {

    @Id
    @Column(name = "invoice_id")
    public UUID invoiceId;
    @Id
    @Column(name = "line_no")
    public int lineNo;
    @Column(nullable = false)
    public String description;
    @Column(nullable = false)
    public int quantity;
    @Column(name = "unit_price_minor", nullable = false)
    public long unitPriceMinor;
    @Column(name = "tax_rate_bps", nullable = false)
    public int taxRateBps;
    @Column(name = "net_minor", nullable = false)
    public long netMinor;
    @Column(name = "tax_minor", nullable = false)
    public long taxMinor;
    @Column(name = "total_minor", nullable = false)
    public long totalMinor;

    /** Composite key (invoice_id, line_no). */
    public static class Key implements Serializable {
        public UUID invoiceId;
        public int lineNo;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && lineNo == k.lineNo && Objects.equals(invoiceId, k.invoiceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(invoiceId, lineNo);
        }
    }
}
