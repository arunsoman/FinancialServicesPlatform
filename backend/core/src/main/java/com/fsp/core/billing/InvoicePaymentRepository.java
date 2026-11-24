package com.fsp.core.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, UUID> {

    /** An invoice's payments, oldest first (bounded: a payment needs a balance left to pay). */
    List<InvoicePayment> findByInvoiceIdOrderByCreatedAtAscIdAsc(UUID invoiceId);

    @Query("select coalesce(sum(p.amountMinor), 0) from InvoicePayment p where p.invoiceId = :invoiceId and p.status in :statuses")
    long sumByStatus(@Param("invoiceId") UUID invoiceId, @Param("statuses") Collection<String> statuses);

    @Query("select count(p) from InvoicePayment p where p.invoiceId = :invoiceId and p.status <> 'FAILED'")
    long countLive(@Param("invoiceId") UUID invoiceId);
}
