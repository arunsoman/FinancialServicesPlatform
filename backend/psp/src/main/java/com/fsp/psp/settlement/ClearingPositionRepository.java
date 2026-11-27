package com.fsp.psp.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClearingPositionRepository extends JpaRepository<ClearingPosition, UUID> {

    Optional<ClearingPosition> findByConnectorCodeAndCurrencyAndPositionDate(
        String connectorCode, String currency, java.time.LocalDate positionDate);

    /** Recon face (Stage 6): drained rows are settled; survivors are today-pending or broken. */
    java.util.List<ClearingPosition> findBySettledFalseOrderByPositionDateAscId();
}
