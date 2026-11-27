package com.fsp.psp.acquire;

import com.fsp.psp.common.ApiException;
import com.fsp.psp.connector.BankConnector;
import com.fsp.psp.connector.ConnectorService;
import com.fsp.psp.merchant.Merchant;
import com.fsp.psp.merchant.MerchantRepository;
import com.fsp.psp.merchant.MerchantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * TTL disposition sweeper for CREATED acquisitions (mirror of EMI SagaSweeper): a rail
 * decision that was deferred (backpressure/throttle/unavailable) or came back unknown
 * is re-sent with the SAME deterministic ids. A CREATED saga past the authorization
 * window expires to REJECTED with a stable failure code (nothing ever moved — pass-through).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fsp.psp.acquire", name = "sweeper-enabled",
    havingValue = "true", matchIfMissing = true)
public class AcquireSweeper {

    /** Authorization window: how long a CREATED saga may stay undelivered. */
    private static final Duration AUTH_WINDOW = Duration.ofHours(48);

    private final AcquireService acquireService;
    private final MerchantRepository merchants;
    private final MerchantService merchantService;
    private final ConnectorService connectors;
    private final AcquisitionRepository acquisitions;

    public AcquireSweeper(AcquireService acquireService, MerchantRepository merchants,
                          MerchantService merchantService, ConnectorService connectors,
                          AcquisitionRepository acquisitions) {
        this.acquireService = acquireService;
        this.merchants = merchants;
        this.merchantService = merchantService;
        this.connectors = connectors;
        this.acquisitions = acquisitions;
    }

    @Scheduled(initialDelayString = "${fsp.psp.settlement.sweep-initial-delay-ms:30000}",
        fixedDelayString = "${fsp.psp.settlement.sweep-interval-ms:60000}")
    public void sweep() {
        try {
            List<Acquisition> created = acquisitions
                .findAllByStateOrderById(Acquisition.STATE_CREATED, PageRequest.of(0, 50));
            for (Acquisition a : created) {
                try {
                    retryOrExpire(a);
                } catch (RuntimeException e) {
                    log.warn("psp_create_sweep_failed acq={} err={}", a.id, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.error("psp_sweep_failed err={}", e.getMessage(), e);
        }
    }

    private void retryOrExpire(Acquisition a) {
        if (a.createdAt.isBefore(Instant.now().minus(AUTH_WINDOW))) {
            // Nothing ever moved (pass-through); expiry is a business close, not a failure.
            a.state = Acquisition.STATE_REJECTED;
            a.failureCode = "PSP_AUTH_WINDOW_EXCEEDED";
            a.updatedAt = Instant.now();
            acquisitions.save(a);
            log.warn("psp_auth_window_exceeded acq={}", a.id);
            return;
        }
        Merchant merchant = merchants.findById(a.merchantId).orElse(null);
        if (merchant == null) {
            a.state = Acquisition.STATE_REJECTED;
            a.failureCode = "PSP_MERCHANT_NOT_FOUND";
            a.updatedAt = Instant.now();
            acquisitions.save(a);
            return;
        }
        try {
            MerchantService.requireAcquirable(merchant);
        } catch (ApiException notAcquirable) {
            return; // suspended merchants simply wait; operator re-activation reuses the saga
        }
        try {
            BankConnector connector = connectors.enabledByCode(a.connectorCode);
            acquireService.sendToRail(a, merchant, connector);
        } catch (ApiException connectorUnavailable) {
            log.info("psp_createsweep_deferred acq={} code={}", a.id, connectorUnavailable.getCode());
        }
    }
}
