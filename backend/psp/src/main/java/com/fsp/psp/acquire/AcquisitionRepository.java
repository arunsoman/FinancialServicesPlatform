package com.fsp.psp.acquire;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AcquisitionRepository extends JpaRepository<Acquisition, UUID> {

    Optional<Acquisition> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Acquisition> findByIdempotencyKey(String idempotencyKey);

    Optional<Acquisition> findByMerchantIdAndPspRef(UUID merchantId, String pspRef);

    /** Merchant-scoped history with server-side search/sort (platform §2f). */
    @Query("""
        SELECT a FROM Acquisition a
        WHERE a.merchantId = :merchantId
          AND (:state IS NULL OR a.state = :state)
          AND (:q IS NULL OR LOWER(a.pspRef) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(a.railRef) LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    Page<Acquisition> search(@Param("merchantId") UUID merchantId,
                             @Param("state") String state,
                             @Param("q") String q,
                             Pageable pageable);

    /** Settlement sweep input: captures of one connector+currency cycle awaiting settlement. */
    @Query("""
        SELECT a FROM Acquisition a
        WHERE a.connectorCode = :connectorCode
          AND a.currency = :currency
          AND a.cycleDate = :cycleDate
          AND a.state = 'CAPTURED'
        """)
    List<Acquisition> sweepable(@Param("connectorCode") String connectorCode,
                                @Param("currency") String currency,
                                @Param("cycleDate") LocalDate cycleDate,
                                Pageable pageable);

    /** Settlement sweeper input: CAPTURED acquisitions of closed cycles. */
    java.util.List<Acquisition> findAllByStateAndCycleDateBeforeOrderById(String state,
        java.time.LocalDate before, Pageable pageable);

    /** TTL sweeper input: CREATED sagas, oldest-first, bounded. */
    java.util.List<Acquisition> findAllByStateOrderById(String state, Pageable pageable);

    /** Statement matching anchor: scheme rail refs are unique per acquisition. */
    java.util.List<Acquisition> findAllByRailRef(String railRef);

    /** Invariant helper: count acquisitions captured in a cycle that are not yet settled. */
    long countByConnectorCodeAndCurrencyAndCycleDateAndState(String connectorCode,
        String currency, LocalDate cycleDate, String state);
    /** Recon face (Stage 6): captures still pending past the settle grace. */
    long countByStateAndCycleDateBefore(String state, java.time.LocalDate cycleDate);
}
