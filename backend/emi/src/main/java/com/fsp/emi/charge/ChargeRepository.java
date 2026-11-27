package com.fsp.emi.charge;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ChargeRepository extends JpaRepository<Charge, UUID> {

    /** Charge idempotency anchor — UNIQUE (wallet_id, order_ref). */
    Optional<Charge> findByWalletIdAndOrderRef(UUID walletId, String orderRef);

    Optional<Charge> findById(UUID id);

    /** §2f: server-side listing with sort whitelist; q searches the order reference. */
    @Query("""
        SELECT c FROM Charge c
        WHERE (:walletId IS NULL OR c.walletId = :walletId)
          AND (:state IS NULL OR c.state = :state)
          AND (:q IS NULL OR LOWER(c.orderRef) LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    Page<Charge> search(@Param("walletId") UUID walletId, @Param("state") String state,
                        @Param("q") String q, Pageable pageable);
}
