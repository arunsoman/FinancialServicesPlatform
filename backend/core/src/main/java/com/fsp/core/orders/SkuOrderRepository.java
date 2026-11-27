package com.fsp.core.orders;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Data access for SKU orders. Search is server-side (pagination/sort/q per §2f). */
public interface SkuOrderRepository extends JpaRepository<SkuOrder, UUID> {

    /** Idempotency anchor: one live order per (buyer, orderRef) — replays return the original. */
    Optional<SkuOrder> findByBuyerPersonIdAndOrderRef(UUID buyerPersonId, String orderRef);

    /**
     * Tenant-subtree-scoped listing; q searches orderRef/skuCode; state filters exactly.
     * Caller bounds page size; sort field whitelist lives in the controller.
     */
    @Query("""
        select o from SkuOrder o
        where o.tenantId = :tenantId
          and (:state is null or o.state = :state)
          and (:q is null or lower(o.orderRef) like lower(concat('%', :q, '%'))
               or lower(o.skuCode) like lower(concat('%', :q, '%')))
        """)
    Page<SkuOrder> search(@Param("tenantId") UUID tenantId,
                          @Param("state") String state,
                          @Param("q") String q,
                          Pageable pageable);
}
