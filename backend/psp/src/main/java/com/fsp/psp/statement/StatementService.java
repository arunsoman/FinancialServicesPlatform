package com.fsp.psp.statement;

import com.fsp.psp.acquire.Acquisition;
import com.fsp.psp.acquire.AcquisitionRepository;
import com.fsp.psp.audit.PspEvent;
import com.fsp.psp.audit.PspEventRepository;
import com.fsp.psp.common.ApiException;
import com.fsp.psp.common.KeyLocks;
import com.fsp.psp.outbox.PspOutbox;
import com.fsp.psp.outbox.PspOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recon statement ingest (plan §3.4.4 / §11 Stage 5): a bank statement is hash-claimed
 * ONCE — sha256 over the canonical payload; a replay returns the original report without
 * re-processing (partial-file tolerant). Rows carry {@code rail_ref} + {@code amount_minor};
 * matching is against captured/settled acquisitions of the same connector.
 */
@Slf4j
@Service
public class StatementService {

    private final StatementIngestRepository ingests;
    private final AcquisitionRepository acquisitions;
    private final PspEventRepository events;
    private final PspOutboxRepository outbox;
    private final KeyLocks locks;
    private final TransactionTemplate tx;

    public StatementService(StatementIngestRepository ingests,
                            AcquisitionRepository acquisitions, PspEventRepository events,
                            PspOutboxRepository outbox, KeyLocks locks, TransactionTemplate tx) {
        this.ingests = ingests;
        this.acquisitions = acquisitions;
        this.events = events;
        this.outbox = outbox;
        this.locks = locks;
        this.tx = tx;
    }

    public record StatementRow(String railRef, long amountMinor) {}

    public record IngestReport(UUID id, String connectorCode, String source, String fileHash,
                               int rowsTotal, int rowsMatched, int rowsMismatched,
                               List<Map<String, Object>> mismatches, boolean replay) {}

    /**
     * Hash-claim-once ingest. Two callers posting the same payload converge on one row;
     * the second receives {@code replay=true} with the original counts — never a re-match,
     * because the claim is the UNIQUE(file_hash) constraint itself.
     */
    public IngestReport ingest(String connectorCode, String source, List<StatementRow> rows) {
        if (connectorCode == null || connectorCode.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_INPUT_INVALID",
                "connectorCode is required");
        }
        if (source == null || source.isBlank() || rows == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_INPUT_INVALID",
                "source and rows are required");
        }
        if (rows.size() > 10_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_INPUT_INVALID",
                "statement exceeds 10k rows — ingest in daily files");
        }

        String fileHash = hashOf(connectorCode, source, rows);

        return locks.withLock("statement", fileHash, () -> {
            var byHash = ingests.findByFileHash(fileHash);
            if (byHash.isPresent()) {
                var r = byHash.get();
                return new IngestReport(r.id, r.connectorCode, r.source, r.fileHash,
                    r.rowsTotal, r.rowsMatched, r.rowsMismatched, List.of(), true);
            }

            int matched = 0;
            int mismatched = 0;
            List<Map<String, Object>> mismatches = new ArrayList<>();
            for (StatementRow row : rows) {
                if (row.railRef() == null || row.railRef().isBlank()) {
                    mismatched++;
                    mismatches.add(Map.of("reason", "MISSING_RAIL_REF"));
                    continue;
                }
                var acq = acquisitions.findAllByRailRef(row.railRef());
                if (acq.size() == 1
                        && acq.get(0).gross() == row.amountMinor()
                        && isRecognizedState(acq.get(0).state)) {
                    matched++;
                } else {
                    mismatched++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reason", acq.isEmpty() ? "UNKNOWN_RAIL_REF"
                        : acq.size() > 1 ? "AMBIGUOUS_RAIL_REF" : "AMOUNT_OR_STATE_DRIFT");
                    m.put("railRef", row.railRef());
                    mismatches.add(m);
                    // Rail refs are scheme tokens, not PII — safe to log the token only.
                    log.warn("psp_statement_mismatch connector={} ref={} reason={}",
                        connectorCode, row.railRef(), m.get("reason"));
                }
            }

            final int m2 = matched;
            final int x2 = mismatched;
            return tx.execute(s -> {
                StatementIngest r = new StatementIngest();
                r.id = UUID.randomUUID();
                r.connectorCode = connectorCode.strip();
                r.source = source.strip();
                r.fileHash = fileHash;
                r.rowsTotal = rows.size();
                r.rowsMatched = m2;
                r.rowsMismatched = x2;
                r.ingestedAt = Instant.now();
                ingests.save(r);

                PspEvent e = new PspEvent();
                e.kind = "STATEMENT_INGESTED";
                e.detail = "hash=" + fileHash;
                e.createdAt = Instant.now();
                events.save(e);

                PspOutbox o = new PspOutbox();
                o.id = UUID.randomUUID();
                o.aggregateId = r.id;
                o.kind = "STATEMENT_INGESTED";
                o.payloadJson = """
                    {"ingestId":"%s","connectorCode":"%s","rowsTotal":%d,"rowsMatched":%d,"rowsMismatched":%d}
                    """.formatted(r.id, r.connectorCode, r.rowsTotal, r.rowsMatched,
                    r.rowsMismatched);
                o.createdAt = Instant.now();
                outbox.save(o);

                return new IngestReport(r.id, r.connectorCode, r.source, r.fileHash,
                    r.rowsTotal, r.rowsMatched, r.rowsMismatched, mismatches, false);
            });
        });
    }

    /** Recon-recognized states: the money either sits in clearing or has swept to payable. */
    private static boolean isRecognizedState(String state) {
        return Acquisition.STATE_CAPTURED.equals(state)
            || Acquisition.STATE_SETTLED.equals(state);
    }

    private static String hashOf(String connectorCode, String source, List<StatementRow> rows) {
        StringBuilder canonical = new StringBuilder("psp-statement|")
            .append(connectorCode.strip()).append('|').append(source.strip())
            .append('|').append(rows.size());
        // Deterministic ordering: hash is defined over the sorted row multiset.
        List<String> sorted = rows.stream()
            .map(r -> (r.railRef() == null ? "" : r.railRef().strip()) + "|" + r.amountMinor())
            .sorted()
            .toList();
        for (String s : sorted) {
            canonical.append('|').append(s);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("psp_statement_hash_failed", e);
        }
    }
}
