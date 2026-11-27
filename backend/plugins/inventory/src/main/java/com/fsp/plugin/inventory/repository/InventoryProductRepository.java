package com.fsp.plugin.inventory.repository;

import com.fsp.plugin.inventory.domain.InventoryProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InventoryProductRepository extends JpaRepository<InventoryProduct, UUID> {

    Optional<InventoryProduct> findByIdAndOwnerTenantId(UUID id, UUID ownerTenantId);

    boolean existsByOwnerTenantIdAndCode(UUID ownerTenantId, String code);
    @Query("""
           SELECT e FROM InventoryProduct e
           WHERE e.ownerTenantId = :tenant
             AND (:status IS NULL OR e.status = :status)
             AND (:hasQ = false OR lower(e.code) LIKE :qPattern OR lower(e.name) LIKE :qPattern)""")
    Page<InventoryProduct> search(@Param("tenant") UUID tenant, @Param("hasQ") boolean hasQ, @Param("qPattern") String qPattern,
                     @Param("status") String status, Pageable pageable);

}