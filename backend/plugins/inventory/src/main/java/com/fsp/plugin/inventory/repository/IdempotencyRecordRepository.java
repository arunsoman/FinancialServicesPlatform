package com.fsp.plugin.inventory.repository;

import com.fsp.plugin.inventory.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
    Optional<IdempotencyRecord> findByPersonIdAndRequestKey(UUID personId, String requestKey);
}
