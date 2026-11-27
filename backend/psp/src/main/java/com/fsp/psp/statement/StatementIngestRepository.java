package com.fsp.psp.statement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StatementIngestRepository extends JpaRepository<StatementIngest, UUID> {

    Optional<StatementIngest> findByFileHash(String fileHash);
}
