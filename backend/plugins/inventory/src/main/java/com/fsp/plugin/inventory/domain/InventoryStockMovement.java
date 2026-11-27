package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Append-only stock movement history for a batch/product (GAP-INV: movements view). */
@Entity
@Table(name = "inventory_stock_movements")
public class InventoryStockMovement {

    public static final String RECEIPT = "RECEIPT";
    public static final String CONSUME = "CONSUME";
    public static final String DISPOSE = "DISPOSE";

    @Id
    public UUID id;
    @Column(name = "owner_tenant_id", nullable = false)
    public UUID ownerTenantId;
    @Column(name = "batch_id", nullable = false)
    public UUID batchId;
    @Column(name = "product_id", nullable = false)
    public UUID productId;
    @Column(nullable = false)
    public String direction;
    @Column(nullable = false)
    public int quantity;
    @Column
    public String reference;
    @Column(name = "actor_id")
    public UUID actorId;
    @Column(name = "moved_at", nullable = false)
    public Instant movedAt = Instant.now();
}
