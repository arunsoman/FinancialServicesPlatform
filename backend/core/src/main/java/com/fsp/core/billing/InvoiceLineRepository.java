package com.fsp.core.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, InvoiceLine.Key> {

    /** Bounded by construction: an invoice carries at most {@link InvoiceMath#MAX_LINES} lines. */
    List<InvoiceLine> findByInvoiceIdOrderByLineNo(UUID invoiceId);
}
