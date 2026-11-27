package com.fsp.emi.bill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedule wrapper for the saga disposition sweeper (plan §5.3). All engine exceptions
 * are caught here — a throwing sweep must never kill the scheduler thread.
 */
@Slf4j
@Component
public class SagaSweeper {

    private final BillPaymentSagaEngine engine;

    public SagaSweeper(BillPaymentSagaEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${fsp.emi.saga.sweep-interval-ms:60000}",
               initialDelayString = "${fsp.emi.saga.sweep-initial-delay-ms:30000}")
    public void sweep() {
        try {
            engine.sweepExpired();
        } catch (RuntimeException e) {
            log.error("bill_saga_sweep_failed err={}", e.getMessage(), e);
        }
    }
}
