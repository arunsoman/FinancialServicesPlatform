package com.fsp.psp.recon;

import com.fsp.psp.acquire.Acquisition;
import com.fsp.psp.acquire.AcquisitionRepository;
import com.fsp.psp.common.PspUuid5;
import com.fsp.psp.config.PspProperties;
import com.fsp.psp.ledger.PspTbService;
import com.fsp.psp.merchant.MerchantRepository;
import com.fsp.psp.settlement.ClearingPosition;
import com.fsp.psp.settlement.ClearingPositionRepository;
import com.fsp.psp.settlement.MerchantPayable;
import com.fsp.psp.settlement.MerchantPayableRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Assembles the read-only recon snapshot (plan Stage 6; chapter 13 §8). Every query is a
 * bounded, deterministically-sorted page (platform §2f); every TB read is outside any DB
 * transaction (§2d). This service never mutates — it is a pure projection of book truth.
 */
@Service
public class PspReconService {

    /** Recon faces never page beyond this bound — the worker re-polls on its schedule. */
    static final int FACE_BOUND = 100;

    private final ClearingPositionRepository positions;
    private final MerchantPayableRepository payables;
    private final AcquisitionRepository acquisitions;
    private final MerchantRepository merchants;
    private final PspTbService tb;
    private final PspProperties props;

    public PspReconService(ClearingPositionRepository positions, MerchantPayableRepository payables,
                           AcquisitionRepository acquisitions, MerchantRepository merchants,
                           PspTbService tb, PspProperties props) {
        this.positions = positions;
        this.payables = payables;
        this.acquisitions = acquisitions;
        this.merchants = merchants;
        this.tb = tb;
        this.props = props;
    }

    public PspReconSnapshot.Snapshot snapshot() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // 1. Unsettled clearing positions — the sweep drains each settled cycle to zero,
        //    so only today-pending cycles or broken ones appear here.
        List<PspReconSnapshot.Position> positionViews = new ArrayList<>();
        for (ClearingPosition p : positions.findBySettledFalseOrderByPositionDateAscId()) {
            String tbNet = tb.lookupBalance(p.tbAccountId)
                .map(b -> Long.toString(b.net())).orElse(null); // unknown = cannot prove
            positionViews.add(new PspReconSnapshot.Position(p.connectorCode, p.currency,
                p.positionDate,
                p.amountMinor.toPlainString(), p.settled, tbNet));
        }

        // 2. Overdue payables (PSD-1 §12.2.3): outstanding accrual past merchant pay-over.
        List<PspReconSnapshot.PayableOverdue> overdueViews = new ArrayList<>();
        Map<UUID, Integer> payOverByMerchant = new HashMap<>();
        var payablePage = payables.findAccruing(
            PageRequest.of(0, FACE_BOUND, Sort.by(Sort.Direction.ASC, "periodStart", "id")));
        for (MerchantPayable payable : payablePage) {
            int payOver = payOverByMerchant.computeIfAbsent(payable.merchantId, id ->
                merchants.findById(id).map(m -> m.payOverDays).orElse(2));
            int daysOver = (int) ChronoUnit.DAYS.between(payable.periodStart, today) - payOver;
            if (daysOver > 0) {
                overdueViews.add(new PspReconSnapshot.PayableOverdue(payable.merchantId,
                    payable.currency, payable.periodStart, payable.accrualMinor.toPlainString(),
                    payable.paidMinor.toPlainString(), daysOver));
            }
        }

        // 3. Captures past the settle grace still unswept (sweep latency or breakage).
        long graceDays = Math.max(1, (long) Math.ceil((double) props.getSettlement().getSettleGraceSeconds() / 86_400.0));
        long staleCaptures = acquisitions.countByStateAndCycleDateBefore(
            Acquisition.STATE_CAPTURED, today.minusDays(graceDays));

        // 4. Float net per currency (float account is debited on capture; its posted net
        //    equals the funds currently parked on the PSP book).
        List<PspReconSnapshot.FloatLine> floats = new ArrayList<>();
        for (String currency : distinctCurrencies(positionViews, overdueViews)) {
            String net = tb.lookupBalance(PspUuid5.settlementFloatAccountId(currency))
                .map(b -> Long.toString(b.net())).orElse(null); // unknown = cannot prove
            floats.add(new PspReconSnapshot.FloatLine(currency, net));
        }

        return new PspReconSnapshot.Snapshot(Instant.now(), List.copyOf(positionViews),
            List.copyOf(overdueViews), staleCaptures, List.copyOf(floats));
    }

    private static Set<String> distinctCurrencies(
            List<PspReconSnapshot.Position> positions,
            List<PspReconSnapshot.PayableOverdue> overdue) {
        Set<String> out = new TreeSet<>();
        positions.forEach(p -> out.add(p.currency()));
        overdue.forEach(p -> out.add(p.currency()));
        return out;
    }
}
