package com.fsp.plugin.inventory.repository;

import com.fsp.plugin.inventory.domain.InventoryEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InventoryEventRepository extends JpaRepository<InventoryEvent, UUID> {
}
