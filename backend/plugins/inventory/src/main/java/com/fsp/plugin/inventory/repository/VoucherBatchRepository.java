package com.fsp.plugin.inventory.repository;

import com.fsp.plugin.inventory.domain.VoucherBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VoucherBatchRepository extends JpaRepository<VoucherBatch, UUID> {

    Optional<VoucherBatch> findByIdAndOwnerTenantId(UUID id, UUID ownerTenantId);
    @Query("""
           SELECT e FROM VoucherBatch e
           WHERE e.ownerTenantId = :tenant
             AND (:status IS NULL OR e.status = :status)
             AND (:hasQ = false OR lower(e.reference) LIKE :qPattern OR lower(e.productCode) LIKE :qPattern)""")
    Page<VoucherBatch> search(@Param("tenant") UUID tenant, @Param("hasQ") boolean hasQ, @Param("qPattern") String qPattern,
                     @Param("status") String status, Pageable pageable);

}