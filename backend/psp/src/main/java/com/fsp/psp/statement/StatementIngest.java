package com.fsp.psp.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Recon statement ingest journal row — hash-claim-once (V1 table {@code statement_ingest}). */
@Entity
@Table(name = "statement_ingest")
public class StatementIngest {

    @Id
    public UUID id;

    @Column(name = "connector_code", nullable = false)
    public String connectorCode;

    @Column(nullable = false)
    public String source;

    /** sha256 over the canonical payload — a replay re-ingests nothing. */
    @Column(name = "file_hash", nullable = false, unique = true)
    public String fileHash;

    @Column(name = "rows_total", nullable = false)
    public int rowsTotal = 0;

    @Column(name = "rows_matched", nullable = false)
    public int rowsMatched = 0;

    @Column(name = "rows_mismatched", nullable = false)
    public int rowsMismatched = 0;

    @Column(name = "ingested_at", nullable = false)
    public Instant ingestedAt;
}
