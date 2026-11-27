package com.fsp.plugin.inventory.repository;

import com.fsp.plugin.inventory.domain.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    List<Voucher> findByStatusAndReservedAtBefore(String status, java.time.Instant cutoff,
                                                  org.springframework.data.domain.Pageable pageable);

    Optional<Voucher> findFirstByOwnerTenantIdAndBatchIdAndStatusOrderByIdAsc(UUID ownerTenantId,
                                                                             UUID batchId, String status);

    int countByBatchIdAndStatus(UUID batchId, String status);
}
