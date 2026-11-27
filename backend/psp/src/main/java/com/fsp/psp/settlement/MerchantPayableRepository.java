package com.fsp.psp.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface MerchantPayableRepository extends JpaRepository<MerchantPayable, UUID> {

    Optional<MerchantPayable> findByMerchantIdAndCurrencyAndPeriodStartAndPeriodEnd(
        UUID merchantId, String currency, LocalDate periodStart, LocalDate periodEnd);

    /** Merchant payable view with server-side search/sort (platform §2f). */
    @Query("""
        SELECT p FROM MerchantPayable p
        WHERE p.merchantId = :merchantId
          AND (:withBalance IS NULL OR p.accrualMinor <> p.paidMinor)
        """)
    java.util.List<MerchantPayable> forMerchant(@Param("merchantId") UUID merchantId,
                                                @Param("withBalance") Boolean withBalance);

    /** Recon face (Stage 6, PSD-1 §12.2.3 pay-over): outstanding accrual, oldest first. */
    @Query("""
        SELECT p FROM MerchantPayable p
        WHERE p.accrualMinor > p.paidMinor
        """)
    java.util.List<MerchantPayable> findAccruing(org.springframework.data.domain.Pageable page);
}
