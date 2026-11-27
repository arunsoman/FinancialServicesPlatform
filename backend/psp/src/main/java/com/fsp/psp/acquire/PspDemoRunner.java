package com.fsp.psp.acquire;

import com.fsp.psp.connector.BankConnector;
import com.fsp.psp.connector.ConnectorService;
import com.fsp.psp.common.PspUuid5;
import com.fsp.psp.ledger.PspTbResult;
import com.fsp.psp.ledger.PspTbService;
import com.fsp.psp.merchant.Merchant;
import com.fsp.psp.merchant.MerchantService;
import com.fsp.psp.settlement.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Real-TigerBeetle smoke (run with --spring.profiles.active=demo-psp): onboard a demo
 * merchant, acquire, capture on the PSP book, and optionally sweep a closed cycle.
 * Idempotent by unique refs; runtime-only demo data (platform rule: demo data lives only
 * in demo profiles, never SQL seed).
 */
@Component
@Profile("demo-psp")
final class PspDemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PspDemoRunner.class);

    private final ConnectorService connectors;
    private final MerchantService merchants;
    private final AcquireService acquireService;
    private final SettlementService settlements;
    private final PspTbService tb;

    PspDemoRunner(ConnectorService connectors, MerchantService merchants,
                  AcquireService acquireService, SettlementService settlements, PspTbService tb) {
        this.connectors = connectors;
        this.merchants = merchants;
        this.acquireService = acquireService;
        this.settlements = settlements;
        this.tb = tb;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // One-off demo-book hygiene: --psp-demo-repair <acquisitionId>:<amountMinor>
            // posts a manual closing_debit for residue left by an earlier build.
            String repair = argAfter(args, "--psp-demo-repair");
            // spec: <merchantId>:<acquisitionId>:<amountMinor>
            if (repair != null) {
                repairManualClosingDebit(repair);
                return;
            }

            // Demo acquirer connector (in-process deterministic rail; no network).
            connectors.upsert("DEMO-BANK", "AfriqPay Demo Acquirer", "CARD_SCHEME",
                "REST", "DEMO", "FSP_PSP_DEMO_ENDPOINT", BankConnector.STATUS_ENABLED);
            UUID tenantId = UUID.fromString("11111111-2222-4333-8444-555555555555");
            Merchant merchant = merchants.maybeOnboard(tenantId, "demo-merchant",
                "Demo Merchant (PSP)");
            merchants.activateIfPending(tenantId, merchant.id);

            String ref = "ORD-DEMO-" + UUID.randomUUID();
            long gross = 30_00;
            long fee = 300;
            var authorized = acquireService.authorize(tenantId, merchant.id, "DEMO-BANK", ref,
                gross, fee, "NAD", "demo-acq-" + ref);
            var captured = acquireService.capture(tenantId, authorized.acquisition().id);
            log.info("psp_demo_authorize state={} railRef={}",
                authorized.acquisition().state, authorized.acquisition().railRef);
            log.info("psp_demo_capture state={} cycle={} net={}",
                captured.state, captured.cycleDate, captured.net());

            // --psp-demo-sweep: also sweep the demo capture's cycle inline (demo-only;
            // standard sweeps settle closed cycles via the scheduled sweeper on D+1).
            if (args.getSourceArgs() != null
                    && java.util.Arrays.asList(args.getSourceArgs()).contains("--psp-demo-sweep")) {
                LocalDate cycle = captured.cycleDate;
                var r = settlements.sweep("DEMO-BANK", "NAD", cycle);
                log.info("psp_demo_sweep cycle={} settled={} state={} replay={}",
                    r.cycleDate(), r.settledCount(), r.state(), r.replay());
            }
        } catch (Exception e) {
            log.warn("psp_demo_runner_failed err={}", e.getMessage(), e);
        }
    }

    /**
     * Demo-book hygiene (profile-gated, on operator request only): post a manual
     * closing_debit DR clearing → CR merchant payable for ledger residue left by an
     * earlier build, restoring the per-cycle zero-clearing invariant. Idempotent via
     * the deterministic transfer id {@code closingDebitTransferId(acquisitionId)}.
     */
    private void repairManualClosingDebit(String spec) {
        String[] parts = spec.split(":");
        UUID merchantId = UUID.fromString(parts[0].trim());
        UUID acquisitionId = UUID.fromString(parts[parts.length - 2].trim());
        long amountMinor = Long.parseLong(parts[parts.length - 1].trim());
        log.info("psp_demo_repair acq={} amount={}", acquisitionId, amountMinor);
        PspTbResult result = tb.createTransfers(java.util.List.of(new PspTbService.TransferSpec(
            PspUuid5.repairClosingDebitTransferId(acquisitionId, 2),
            PspUuid5.clearingAccountId("DEMO-BANK", "NAD"),
            PspUuid5.merchantPayableAccountId(merchantId, "NAD"),
            amountMinor, tb.bookId(), AcquireService.CODE_CLOSING_DEBIT, 0,
            acquisitionId, 0L, 0, null, 0)));
        log.info("psp_demo_repair_done outcome={} {}",
            result.getClass().getSimpleName(),
            result instanceof PspTbResult.DefinitiveRejection r ? r.detail() : "");
    }

    private static String argAfter(ApplicationArguments args, String name) {
        String[] src = args.getSourceArgs();
        if (src == null) {
            return null;
        }
        for (int i = 0; i < src.length; i++) {
            if (name.equals(src[i]) && i + 1 < src.length) {
                return src[i + 1];
            }
        }
        return null;
    }
}
