package com.fsp.psp.settlement;

import com.fsp.psp.audit.PspEvent;
import com.fsp.psp.audit.PspEventRepository;
import com.fsp.psp.acquire.AcquireService;
import com.fsp.psp.acquire.Acquisition;
import com.fsp.psp.acquire.AcquisitionRepository;
import com.fsp.psp.common.ApiException;
import com.fsp.psp.common.KeyLocks;
import com.fsp.psp.common.PspUuid5;
import com.fsp.psp.config.PspProperties;
import com.fsp.psp.connector.BankConnector;
import com.fsp.psp.connector.ConnectorService;
import com.fsp.psp.ledger.PspTbResult;
import com.fsp.psp.ledger.PspTbService;
import com.fsp.psp.merchant.Merchant;
import com.fsp.psp.merchant.MerchantService;
import com.fsp.psp.outbox.PspOutbox;
import com.fsp.psp.outbox.PspOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Settlement sweep (plan §1.1 / §11 Stage 5): for each (connector, currency, cycle) with
 * captured acquisitions, post-pending commits resolve the capture chain, then the
 * {@code closing_debit} DR clearing net → CR merchant payable drains the nominal clearing
 * account back to <b>zero</b>. The whole sweep is idempotent: the batch row is the
 * claim; TB Exists/LinkedEventChainClosed are success; per-acquisition rows flip
 * CAPTURED → SETTLED with @Version preconditions.
 *
 * <p>Hard invariant asserted at batch close: TB clearing balance (posted) + outstanding
 * position row == ZERO for the cycle — otherwise ERROR {@code psp_sweep_clearing_residual}
 * is logged and recon pages (platform §3.3).</p>
 */
@Slf4j
@Service
public class SettlementService {

    private final SettlementBatchRepository batches;
    private final AcquisitionRepository acquisitions;
    private final ClearingPositionRepository positions;
    private final MerchantPayableRepository payables;
    private final MerchantService merchants;
    private final ConnectorService connectors;
    private final AcquireService acquireService;
    private final PspTbService tb;
    private final PspEventRepository events;
    private final PspOutboxRepository outbox;
    private final PspProperties props;
    private final KeyLocks locks;
    private final TransactionTemplate tx;

    public SettlementService(SettlementBatchRepository batches,
                             AcquisitionRepository acquisitions,
                             ClearingPositionRepository positions,
                             MerchantPayableRepository payables,
                             MerchantService merchants, ConnectorService connectors,
                             AcquireService acquireService, PspTbService tb,
                             PspEventRepository events, PspOutboxRepository outbox,
                             PspProperties props, KeyLocks locks, TransactionTemplate tx) {
        this.batches = batches;
        this.acquisitions = acquisitions;
        this.positions = positions;
        this.payables = payables;
        this.merchants = merchants;
        this.connectors = connectors;
        this.acquireService = acquireService;
        this.tb = tb;
        this.events = events;
        this.outbox = outbox;
        this.props = props;
        this.locks = locks;
        this.tx = tx;
    }

    public record SweepResult(UUID batchId, String connectorCode, String currency,
                              LocalDate cycleDate, String state, int settledCount,
                              long settledNetMinor, boolean replay) {}

    /**
     * Sweeps one closed cycle. Serialised per (connector, currency) by KeyLocks so
     * concurrent sweeps never interleave payable-row updates; all rows update in
     * deterministic acquisition-id order (platform §2d deterministic lock order).
     */
    public SweepResult sweep(String connectorCode, String currency, LocalDate cycleDate) {
        if (cycleDate == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_CYCLE_INVALID",
                "cycleDate is required (yyyy-MM-dd)");
        }
        if (!LocalDate.now(ZoneOffset.UTC).isAfter(cycleDate)) {
            throw new ApiException(HttpStatus.CONFLICT, "PSP_CYCLE_OPEN",
                "cycle " + cycleDate + " is still open; sweeps settle closed cycles only");
        }
        String ccy = currency.strip().toUpperCase();
        BankConnector connector = connectors.enabledByCode(connectorCode);

        return locks.withLock("sweep", connectorCode + ":" + ccy, () -> {
            Instant windowStart = cycleDate.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant windowEnd = cycleDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            var existing = batches.findByConnectorCodeAndCurrencyAndWindowStartAndWindowEnd(
                connectorCode, ccy, windowStart, windowEnd);
            if (existing.isPresent()) {
                SettlementBatch b = existing.get();
                if (SettlementBatch.STATE_COMPLETED.equals(b.state)) {
                    long settled = settledNet(connectorCode, ccy, cycleDate);
                    return new SweepResult(b.id, connectorCode, ccy, cycleDate, b.state,
                        (int) acquisitions.countByConnectorCodeAndCurrencyAndCycleDateAndState(
                            connectorCode, ccy, cycleDate, Acquisition.STATE_SETTLED),
                        settled, true);
                }
                if (SettlementBatch.STATE_SWEEPING.equals(b.state)) {
                    // A previous sweep died mid-flight — resume (idempotent by design).
                    return sweepBatch(b, connector, ccy, cycleDate, false);
                }
                return sweepBatch(b, connector, ccy, cycleDate, false);
            }

            SettlementBatch batch = new SettlementBatch();
            batch.id = UUID.randomUUID();
            batch.connectorCode = connectorCode;
            batch.currency = ccy;
            batch.windowStart = windowStart;
            batch.windowEnd = windowEnd;
            batch.state = SettlementBatch.STATE_OPEN;
            batch.createdAt = Instant.now();
            batch.updatedAt = batch.createdAt;
            return sweepBatch(tx.execute(s -> batches.save(batch)), connector, ccy, cycleDate, false);
        });
    }

    private SweepResult sweepBatch(SettlementBatch batch, BankConnector connector,
                                   String ccy, LocalDate cycleDate, boolean replay) {
        int settled = 0;
        long settledNet = 0;

        // Bounded paging (platform §2f): fixed batch size, deterministic sort.
        List<Acquisition> page;
        do {
            page = acquisitions.sweepable(batch.connectorCode, ccy, cycleDate,
                PageRequest.of(0, props.getSettlement().getSweepBatchSize()));
            for (Acquisition a : page.stream()
                    .sorted(java.util.Comparator.comparing(x -> x.id)).toList()) {
                var outcome = settleOne(a);
                if (outcome != null) {
                    settled++;
                    settledNet += outcome;
                }
            }
        } while (page.size() == props.getSettlement().getSweepBatchSize());

        final long settledNetFinal = settledNet;
        final int settledFinal = settled;
        return locks.withLock("sweep-batch", batch.id, () -> {
            SettlementBatch current = tx.execute(s -> {
                batch.state = SettlementBatch.STATE_COMPLETED;
                batch.updatedAt = Instant.now();
                return batches.save(batch);
            });
            assertClearingZero(batch.connectorCode, ccy, cycleDate, settledFinal);
            return new SweepResult(current.id, current.connectorCode, ccy, cycleDate,
                current.state, settledFinal, settledNetFinal, replay);
        });
    }

    /**
     * Settles one acquisition: commits both legs at the ledger (TB call OUTSIDE any DB
     * transaction — platform §2d), then the closing_debit (DR clearing net → CR merchant
     * payable) as a plain transfer, then one DB transaction that flips the row SETTLED,
     * decrements the clearing position, accrues the payable and emits outbox + audit.
     */
    private Long settleOne(Acquisition a) {
        return locks.withLock("settle-acq", a.id, () -> {
            var fresh = acquisitions.findById(a.id);
            if (fresh.isEmpty() || !Acquisition.STATE_CAPTURED.equals(fresh.get().state)) {
                return null; // already settled/voided by an earlier pass — idempotent skip
            }
            Acquisition acq = fresh.get();

            Merchant merchant = merchants.byId(acq.tenantId, acq.merchantId);
            MerchantService.requireAcquirable(merchant);

            acquireService.ensureBookAccounts(acq);
            PspTbResult commits = tb.createTransfers(acquireService.commitLegSpecs(acq));
            switch (commits) {
                case PspTbResult.Ok ok -> { /* chain resolved → closing debit next */ }
                case PspTbResult.DefinitiveRejection rejected -> {
                    handleExpiredOrRejected(acq, rejected.detail());
                    return null;
                }
                case PspTbResult.UnknownTransport unknown -> {
                    log.warn("psp_settle_commit_unknown acq={}", acq.id);
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PSP_TB_UNKNOWN",
                        "commit outcome unknown; sweep retries idempotently");
                }
            }

            // closing_debit: clearing net → merchant payable (plain transfer).
            PspTbResult closing = tb.createTransfers(List.of(new PspTbService.TransferSpec(
                PspUuid5.closingDebitTransferId(acq.id),
                PspUuid5.clearingAccountId(acq.connectorCode, acq.currency),
                PspUuid5.merchantPayableAccountId(acq.merchantId, acq.currency),
                acq.net(), tb.bookId(), AcquireService.CODE_CLOSING_DEBIT, 0,
                acq.id, 0L, 0, null, 0)));
            switch (closing) {
                case PspTbResult.Ok ok -> { /* done */ }
                case PspTbResult.DefinitiveRejection rejected -> {
                    log.warn("psp_settle_closing_rejected acq={} detail={}", acq.id, rejected.detail());
                    throw new ApiException(HttpStatus.CONFLICT, "PSP_TB_REJECTED",
                        "closing debit rejected by ledger");
                }
                case PspTbResult.UnknownTransport unknown -> {
                    log.warn("psp_settle_closing_unknown acq={}", acq.id);
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PSP_TB_UNKNOWN",
                        "closing outcome unknown; sweep retries idempotently");
                }
            }

            long net = acq.net();
            LocalDate cycle = acq.cycleDate;
            return locks.withLock("payable-accrual", acq.merchantId, () ->
                tx.execute(s -> acqTxnal(acq, merchant, net, cycle)));
        });
    }

    /** Row updates with @Version preconditions — called inside one short DB transaction. */
    Long acqTxnal(Acquisition acq, Merchant merchant, long net, LocalDate cycle) {
        acq.state = Acquisition.STATE_SETTLED;
        acq.updatedAt = Instant.now();
        acquisitions.save(acq);

        // Clearing position: the capture parked the GROSS pending in clearing; the sweep
        // drains the whole gross (commit posts gross in, fee-extraction + closing-debit
        // drain fee + net out), so the mirror decrements by gross — never by net.
        ClearingPosition position = positions
            .findByConnectorCodeAndCurrencyAndPositionDate(acq.connectorCode, acq.currency, cycle)
            .orElseGet(() -> {
                // Captures must have created it; a missing row heals here deterministically.
                ClearingPosition p = new ClearingPosition();
                p.id = UUID.randomUUID();
                p.connectorCode = acq.connectorCode;
                p.currency = acq.currency;
                p.positionDate = cycle;
                p.tbAccountId = PspUuid5.clearingAccountId(acq.connectorCode, acq.currency);
                p.amountMinor = BigDecimal.ZERO;
                p.createdAt = Instant.now();
                p.updatedAt = p.createdAt;
                return p;
            });
        position.amountMinor = position.amountMinor.subtract(acq.grossMinor);
        if (position.amountMinor.signum() == 0) {
            position.settled = true;
        }
        position.updatedAt = Instant.now();
        positions.save(position);

        // Merchant payable accrual — period = the settlement cycle day.
        MerchantPayable payable = payables
            .findByMerchantIdAndCurrencyAndPeriodStartAndPeriodEnd(
                acq.merchantId, acq.currency, cycle, cycle)
            .orElseGet(() -> {
                MerchantPayable p = new MerchantPayable();
                p.id = UUID.randomUUID();
                p.merchantId = acq.merchantId;
                p.currency = acq.currency;
                p.periodStart = cycle;
                p.periodEnd = cycle;
                p.accrualMinor = BigDecimal.ZERO;
                p.paidMinor = BigDecimal.ZERO;
                p.createdAt = Instant.now();
                p.updatedAt = p.createdAt;
                return p;
            });
        payable.accrualMinor = payable.accrualMinor.add(BigDecimal.valueOf(net));
        payable.updatedAt = Instant.now();
        payables.save(payable);

        PspEvent e = new PspEvent();
        e.acquisitionId = acq.id;
        e.merchantId = acq.merchantId;
        e.kind = "ACQUISITION_SETTLED";
        e.fromState = Acquisition.STATE_CAPTURED;
        e.toState = Acquisition.STATE_SETTLED;
        e.createdAt = Instant.now();
        events.save(e);

        PspOutbox o = new PspOutbox();
        o.id = UUID.randomUUID();
        o.aggregateId = acq.id;
        o.kind = "ACQUISITION_SETTLED";
        o.payloadJson = """
            {"acquisitionId":"%s","merchantId":"%s","cycleDate":"%s","netMinor":"%d","payOverDays":%d}
            """.formatted(acq.id, acq.merchantId, cycle, net, merchant.payOverDays);
        o.createdAt = Instant.now();
        outbox.save(o);

        return net;
    }

    /**
     * Commit rejected — doctrine R2/R3: a timeout is not a failure. An expired pending
     * chain (sweep missed the grace window) never moved money, so the saga repairs to
     * VOIDED; anything else stays CAPTURED for operator/recon attention.
     */
    private void handleExpiredOrRejected(Acquisition acq, String detail) {
        if (detail != null && detail.contains("pending")) {
            log.warn("psp_settle_pending_expired acq={} repairing to VOIDED", acq.id);
            tx.execute(s -> {
                acq.state = Acquisition.STATE_VOIDED;
                acq.failureCode = "PSP_CAPTURE_TIMEOUT";
                acq.updatedAt = Instant.now();
                acquisitions.save(acq);
                PspEvent e = new PspEvent();
                e.acquisitionId = acq.id;
                e.merchantId = acq.merchantId;
                e.kind = "ACQUISITION_CAPTURE_TIMEOUT";
                e.fromState = Acquisition.STATE_CAPTURED;
                e.toState = Acquisition.STATE_VOIDED;
                e.createdAt = Instant.now();
                events.save(e);
                return acq;
            });
            return;
        }
        log.error("psp_settle_commit_rejected acq={} detail={}", acq.id, detail);
    }

    /** The zero-per-cycle invariant: TB clearing posted balance + position row == 0. */
    private void assertClearingZero(String connectorCode, String ccy, LocalDate cycleDate,
                                    int settledCount) {
        var position =
            positions.findByConnectorCodeAndCurrencyAndPositionDate(connectorCode, ccy, cycleDate);
        long positionAmount = position
            .map(p -> p.amountMinor.longValueExact()).orElse(0L);
        boolean positionSettled = position.map(p -> p.settled).orElse(true);
        UUID clearingAccount = PspUuid5.clearingAccountId(connectorCode, ccy);
        var balance = tb.lookupBalance(clearingAccount);
        if (balance.isEmpty()) {
            log.warn("psp_sweep_clearing_lookup_missing account={} cycle={}", clearingAccount, cycleDate);
            return;
        }
        long tbNet = balance.get().net();
        if (positionAmount != 0 || tbNet != 0 || !positionSettled) {
            log.error("psp_sweep_clearing_residual connector={} cycle={} tbNet={} positionAmount={}",
                connectorCode, cycleDate, tbNet, positionAmount);
        } else if (settledCount > 0) {
            log.info("psp_sweep_clearing_zero connector={} cycle={} settled={} zeroed_invariant=OK",
                connectorCode, cycleDate, settledCount);
        }
    }

    private long settledNet(String connectorCode, String ccy, LocalDate cycleDate) {
        return positions.findByConnectorCodeAndCurrencyAndPositionDate(connectorCode, ccy, cycleDate)
            .map(p -> Optional.ofNullable(p.amountMinor).orElse(BigDecimal.ZERO).longValueExact())
            .orElse(0L);
    }
}
