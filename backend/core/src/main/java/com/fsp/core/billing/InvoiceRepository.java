package com.fsp.core.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Invoices. Pages go through {@link JpaSpecificationExecutor} (filters, search and sort in SQL —
 * AGENTS §2f); the totals bar is one aggregate query; numbers come from an atomic upsert.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    /** Server-computed totals of the open invoices on one side (never summed in Java or the browser). */
    interface Totals {
        long getOutstanding();
        long getOverdue();
        long getDueSoon();
        long getOpenCount();
        long getOverdueCount();
    }

    /**
     * {@code direction}: SALES (seller = org), PURCHASES (buyer = org) or ALL (either party in the
     * org's subtree — {@code subtreePrefix} is the org's hierarchy_path + '/%').
     */
    @Query(nativeQuery = true, value = """
        SELECT COALESCE(SUM(i.total_minor - i.paid_minor), 0)                                         AS outstanding,
               COALESCE(SUM(i.total_minor - i.paid_minor) FILTER (WHERE i.due_date < :today), 0)       AS overdue,
               COALESCE(SUM(i.total_minor - i.paid_minor)
                        FILTER (WHERE i.due_date >= :today AND i.due_date <= :soon), 0)                AS dueSoon,
               COUNT(*)                                                                                AS openCount,
               COUNT(*) FILTER (WHERE i.due_date < :today)                                             AS overdueCount
        FROM invoices i
        WHERE i.status IN ('ISSUED', 'PARTIALLY_PAID')
          AND i.currency = :currency
          AND ( (:direction = 'SALES'     AND i.seller_tenant_id = :org)
             OR (:direction = 'PURCHASES' AND i.buyer_tenant_id  = :org)
             OR (:direction = 'ALL' AND EXISTS (
                   SELECT 1 FROM tenants t
                   WHERE t.id IN (i.seller_tenant_id, i.buyer_tenant_id)
                     AND (t.id = :org OR t.hierarchy_path LIKE :subtreePrefix))) )
        """)
    Totals totals(@Param("org") UUID org, @Param("direction") String direction,
                  @Param("subtreePrefix") String subtreePrefix, @Param("currency") String currency,
                  @Param("today") LocalDate today, @Param("soon") LocalDate soon);

    /** Next running number for (seller, year) — one atomic statement, safe under concurrent issues. */
    @Query(nativeQuery = true, value = """
        INSERT INTO invoice_number_seq (seller_tenant_id, year, last_value) VALUES (:seller, :year, 1)
        ON CONFLICT (seller_tenant_id, year) DO UPDATE SET last_value = invoice_number_seq.last_value + 1
        RETURNING last_value
        """)
    int nextNumber(@Param("seller") UUID seller, @Param("year") int year);

    /** Is {@code tenantId} in the subtree rooted at the org with this path (itself included)? */
    @Query(nativeQuery = true, value = """
        SELECT EXISTS (SELECT 1 FROM tenants t
                       WHERE t.id = :tenantId AND (t.hierarchy_path = :path OR t.hierarchy_path LIKE :subtreePrefix))
        """)
    boolean inSubtree(@Param("tenantId") UUID tenantId, @Param("path") String path,
                      @Param("subtreePrefix") String subtreePrefix);
}
