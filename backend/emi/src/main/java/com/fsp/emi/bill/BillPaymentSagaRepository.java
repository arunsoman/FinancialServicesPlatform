package com.fsp.emi.bill;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillPaymentSagaRepository extends JpaRepository<BillPaymentSaga, UUID> {

    Optional<BillPaymentSaga> findByIdempotencyKey(String idempotencyKey);

    List<BillPaymentSaga> findByState(String state, Pageable pageable);

    /** TTL sweeper windows (V1 idx_saga_state_expires). */
    List<BillPaymentSaga> findByStateAndExpiresAtBefore(String state, Instant cutoff, Pageable pageable);

    List<BillPaymentSaga> findByStateInAndExpiresAtBefore(List<String> states, Instant cutoff, Pageable pageable);

    /**
     * §2f paginated owner history: server-side search (q on accountRef) and filter (state),
     * sorting is whatever the controller passes as Pageable (whitelist-checked there).
     */
    @Query("""
        select s from BillPaymentSaga s where s.walletId = :walletId
        and (:state is null or s.state = :state)
        and (:q is null or lower(s.accountRef) like lower(concat('%', :q, '%')))""")
    Page<BillPaymentSaga> searchByWallet(@Param("walletId") UUID walletId,
                                         @Param("state") String state,
                                         @Param("q") String q,
                                         Pageable pageable);
}
