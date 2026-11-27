package com.fsp.plugin.inventory.repository;

import com.fsp.plugin.inventory.domain.VoucherSale;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VoucherSaleRepository extends JpaRepository<VoucherSale, UUID> {

    Optional<VoucherSale> findByOwnerTenantIdAndSaleReference(UUID ownerTenantId, String saleReference);

    Optional<VoucherSale> findByVoucherId(UUID voucherId);
}
