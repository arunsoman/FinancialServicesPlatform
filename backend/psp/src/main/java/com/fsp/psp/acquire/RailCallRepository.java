package com.fsp.psp.acquire;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RailCallRepository extends JpaRepository<RailCall, UUID> {

    Optional<RailCall> findByCallKey(String callKey);
}
