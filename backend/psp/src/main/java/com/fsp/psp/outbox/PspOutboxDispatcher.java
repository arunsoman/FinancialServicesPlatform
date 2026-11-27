package com.fsp.psp.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * PSP outbox dispatcher (exactly-once layer 4). Phase-0 consumer = the dispatch audit
 * log (contract established by the row shape: kind + payload_json); later consumers
 * (recon-worker, notification plane) subscribe to the same claim semantics.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fsp.psp.outbox", name = "dispatcher-enabled",
    havingValue = "true", matchIfMissing = true)
public class PspOutboxDispatcher {

    private static final int MAX_PER_PASS = 100;

    private final PspOutboxRepository outbox;
    private final TransactionTemplate tx;

    public PspOutboxDispatcher(PspOutboxRepository outbox, TransactionTemplate tx) {
        this.outbox = outbox;
        this.tx = tx;
    }

    @Scheduled(initialDelayString = "${fsp.psp.outbox.sweep-initial-delay-ms:3000}",
        fixedDelayString = "${fsp.psp.outbox.sweep-interval:500}")
    public void dispatch() {
        List<PspOutbox> batch = outbox.findUnclaimed(PageRequest.of(0, MAX_PER_PASS));
        for (PspOutbox row : batch) {
            try {
                tx.executeWithoutResult(s -> {
                    row.attempts = row.attempts + 1;
                    row.claimedAt = Instant.now();
                    outbox.save(row);
                });
                log.info("psp_outbox_dispatch kind={} aggregate={} attempts={}",
                    row.kind, row.aggregateId, row.attempts);
            } catch (RuntimeException e) {
                log.warn("psp_outbox_claim_failed kind={} aggregate={} err={}",
                    row.kind, row.aggregateId, e.getMessage());
            }
        }
    }
}
