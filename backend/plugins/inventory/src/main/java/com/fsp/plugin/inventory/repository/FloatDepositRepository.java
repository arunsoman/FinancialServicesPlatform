package com.fsp.plugin.inventory.repository;

import com.fsp.plugin.inventory.domain.FloatDeposit;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FloatDepositRepository extends JpaRepository<FloatDeposit, UUID> {

    Optional<FloatDeposit> findByIdAndOwnerTenantId(UUID id, UUID ownerTenantId);
    @Query("""
           SELECT e FROM FloatDeposit e
           WHERE e.ownerTenantId = :tenant
             AND (:status IS NULL OR e.status = :status)
             AND (:hasQ = false OR lower(e.floatCode) LIKE :qPattern OR lower(e.note) LIKE :qPattern)""")
    Page<FloatDeposit> search(@Param("tenant") UUID tenant, @Param("hasQ") boolean hasQ, @Param("qPattern") String qPattern,
                     @Param("status") String status, Pageable pageable);

}