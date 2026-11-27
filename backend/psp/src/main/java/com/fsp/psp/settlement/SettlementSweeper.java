package com.fsp.psp.settlement;

import com.fsp.psp.acquire.Acquisition;
import com.fsp.psp.acquire.AcquisitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The settlement sweeper (plan §11 Stage 5). Every pass it sweeps CLOSED cycles that
 * still carry CAPTURED acquisitions — the {@code closing_debit} run that beats PSD-1
 * §12.2.3's 2-business-day pay-over ceiling. Cycle D settles at D+1 (or later) UTC;
 * sweeps of open cycles are refused by {@link SettlementService}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fsp.psp.settlement", name = "sweeper-enabled",
    havingValue = "true", matchIfMissing = true)
public class SettlementSweeper {

    /**
     * Which (connector, currency, cycle) triples to sweep: derived from CAPTURED
     * acquisitions older than today. Bounded: at most {@code batchSize} acquisitions per
     * cycle per pass; the next pass picks up the remainder.
     */
    private static final int MAX_CYCLES_PER_PASS = 20;
    private static final int MAX_CAPTURED_PER_PASS = 50;

    private final AcquisitionRepository acquisitions;
    private final SettlementService settlements;

    public SettlementSweeper(AcquisitionRepository acquisitions, SettlementService settlements) {
        this.acquisitions = acquisitions;
        this.settlements = settlements;
    }

    @Scheduled(initialDelayString = "${fsp.psp.settlement.sweep-initial-delay-ms:30000}",
        fixedDelayString = "${fsp.psp.settlement.sweep-interval-ms:60000}")
    public void sweepDueCycles() {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            List<Acquisition> due = acquisitions
                .findAllByStateAndCycleDateBeforeOrderById(Acquisition.STATE_CAPTURED,
                    today, PageRequest.of(0, MAX_CAPTURED_PER_PASS));
            List<String[]> cycles = due.stream()
                .map(a -> new String[] {a.connectorCode, a.currency, a.cycleDate.toString()})
                .distinct()
                .limit(MAX_CYCLES_PER_PASS)
                .toList();
            for (String[] cycle : cycles) {
                LocalDate cycleDate = LocalDate.parse(cycle[2]);
                try {
                    var result = settlements.sweep(cycle[0], cycle[1], cycleDate);
                    log.info("psp_settlement_sweep connector={} cycle={} settled={} net={} replay={}",
                        cycle[0], cycle[2], result.settledCount(), result.settledNetMinor(),
                        result.replay());
                } catch (RuntimeException e) {
                    log.warn("psp_settlement_sweep_deferred connector={} cycle={} err={}",
                        cycle[0], cycle[2], e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.error("psp_settlement_sweep_failed err={}", e.getMessage(), e);
        }
    }
}
