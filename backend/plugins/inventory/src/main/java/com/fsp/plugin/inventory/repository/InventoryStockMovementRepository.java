package com.fsp.plugin.inventory.repository;

import com.fsp.plugin.inventory.domain.InventoryStockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InventoryStockMovementRepository extends JpaRepository<InventoryStockMovement, UUID> {

    List<InventoryStockMovement> findByBatchIdAndOwnerTenantIdOrderByMovedAtDesc(UUID batchId, UUID ownerTenantId);
}
