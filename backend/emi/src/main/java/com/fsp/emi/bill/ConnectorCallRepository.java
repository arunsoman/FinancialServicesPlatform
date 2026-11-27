package com.fsp.emi.bill;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConnectorCallRepository extends JpaRepository<ConnectorCall, UUID> {

    Optional<ConnectorCall> findByKindAndIdempotencyKey(String kind, String idempotencyKey);
}
