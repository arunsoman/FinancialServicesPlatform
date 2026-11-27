package com.fsp.psp.merchant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    Optional<Merchant> findByMerchantCode(String merchantCode);

    Optional<Merchant> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Tenant-subtree-scoped list with server-side search (platform §2f). */
    @Query("""
        SELECT m FROM Merchant m
        WHERE m.tenantId = :tenantId
          AND (:q IS NULL OR LOWER(m.merchantCode) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(m.displayName) LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    Page<Merchant> search(@Param("tenantId") UUID tenantId, @Param("q") String q, Pageable pageable);
}
