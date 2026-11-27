package com.fsp.psp.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {

    Optional<SettlementBatch> findByConnectorCodeAndCurrencyAndWindowStartAndWindowEnd(
        String connectorCode, String currency, Instant windowStart, Instant windowEnd);

    @Query("""
        SELECT DISTINCT s.connectorCode, s.currency, s.windowStart
        FROM SettlementBatch s
        WHERE s.state IN ('OPEN', 'SWEEPING', 'FAILED')
        """)
    java.util.List<Object[]> unfinishedBatches();
}
