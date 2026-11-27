package com.fsp.psp.recon;

import com.fsp.psp.acquire.AcquisitionRepository;
import com.fsp.psp.config.PspProperties;
import com.fsp.psp.ledger.PspTbService;
import com.fsp.psp.merchant.Merchant;
import com.fsp.psp.merchant.MerchantRepository;
import com.fsp.psp.settlement.ClearingPosition;
import com.fsp.psp.settlement.ClearingPositionRepository;
import com.fsp.psp.settlement.MerchantPayable;
import com.fsp.psp.settlement.MerchantPayableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PSP recon face (Stage 6): honest book-truth projection — unsettled positions with TB
 * net, overdue payables per merchant pay-over ceiling, stale captures, float nets. Money
 * leaves the service as STRING (§2b).
 */
class PspReconServiceTest {

    ClearingPositionRepository positions;
    MerchantPayableRepository payables;
    AcquisitionRepository acquisitions;
    MerchantRepository merchants;
    PspTbService tb;
    PspReconService service;

    static final UUID MERCHANT = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @BeforeEach
    void setUp() {
        positions = mock(ClearingPositionRepository.class);
        payables = mock(MerchantPayableRepository.class);
        acquisitions = mock(AcquisitionRepository.class);
        merchants = mock(MerchantRepository.class);
        tb = mock(PspTbService.class);
        service = new PspReconService(positions, payables, acquisitions, merchants, tb,
            new PspProperties());
        when(payables.findAccruing(any())).thenReturn(List.of());
        when(positions.findBySettledFalseOrderByPositionDateAscId()).thenReturn(List.of());
        when(merchants.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void unsettledPositionCarriesTbNetAndStringMoney() {
        ClearingPosition p = new ClearingPosition();
        p.id = UUID.randomUUID();
        p.connectorCode = "DEMO-BANK";
        p.currency = "NAD";
        p.positionDate = LocalDate.now(ZoneOffset.UTC);
        p.tbAccountId = UUID.randomUUID();
        p.amountMinor = new BigDecimal(3000);
        p.settled = false;
        p.createdAt = Instant.now();
        when(positions.findBySettledFalseOrderByPositionDateAscId()).thenReturn(List.of(p));
        when(tb.lookupBalance(p.tbAccountId)).thenReturn(Optional.of(
            new PspTbService.Balance(0, 3000, 0, 3000)));

        var snap = service.snapshot();

        assertThat(snap.positions()).hasSize(1);
        var view = snap.positions().get(0);
        assertThat(view.tbNet()).isEqualTo("-3000");
        assertThat(view.amountMinor()).isEqualTo("3000");
        assertThat(view.settled()).isFalse();
    }

    @Test
    void overduePayableRespectsMerchantCeiling() {
        MerchantPayable payable = new MerchantPayable();
        payable.id = UUID.randomUUID();
        payable.merchantId = MERCHANT;
        payable.currency = "NAD";
        payable.periodStart = LocalDate.now(ZoneOffset.UTC).minusDays(9);
        payable.periodEnd = payable.periodStart.plusDays(1);
        payable.accrualMinor = new BigDecimal(2700);
        payable.paidMinor = BigDecimal.ZERO;
        payable.createdAt = Instant.now();
        payable.updatedAt = payable.createdAt;
        when(payables.findAccruing(any())).thenReturn(List.of(payable));
        Merchant merchant = new Merchant();
        merchant.id = MERCHANT;
        merchant.payOverDays = 2;
        when(merchants.findById(MERCHANT)).thenReturn(Optional.of(merchant));

        var snap = service.snapshot();

        assertThat(snap.payablesOverdue()).hasSize(1);
        var view = snap.payablesOverdue().get(0);
        assertThat(view.daysOver()).isEqualTo(7); // 9 elapsed days − 2-day ceiling
        assertThat(view.accrualMinor()).isEqualTo("2700");
    }

    @Test
    void payableWithinCeilingIsNotOverdue() {
        MerchantPayable payable = new MerchantPayable();
        payable.id = UUID.randomUUID();
        payable.merchantId = MERCHANT;
        payable.currency = "NAD";
        payable.periodStart = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        payable.periodEnd = LocalDate.now(ZoneOffset.UTC);
        payable.accrualMinor = new BigDecimal(2700);
        payable.paidMinor = BigDecimal.ZERO;
        payable.createdAt = Instant.now();
        payable.updatedAt = payable.createdAt;
        when(payables.findAccruing(any())).thenReturn(List.of(payable));

        assertThat(service.snapshot().payablesOverdue()).isEmpty();
    }

    @Test
    void staleCapturesCountPastGrace() {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(2);
        when(acquisitions.countByStateAndCycleDateBefore("CAPTURED", cutoff)).thenReturn(4L);
        assertThat(service.snapshot().staleCaptures()).isEqualTo(4L);
    }

    @Test
    void floatLinesReportPerCurrencyNet() {
        when(tb.lookupBalance(
            com.fsp.psp.common.PspUuid5.settlementFloatAccountId("NAD")))
            .thenReturn(Optional.of(new PspTbService.Balance(9000, 6000, 0, 0)));

        // currency presence comes from an unsettled position; floats follow the book's currencies
        ClearingPosition p = new ClearingPosition();
        p.id = UUID.randomUUID();
        p.connectorCode = "DEMO-BANK";
        p.currency = "NAD";
        p.positionDate = LocalDate.now(ZoneOffset.UTC);
        p.tbAccountId = UUID.randomUUID();
        p.amountMinor = BigDecimal.ZERO;
        p.settled = false;
        when(positions.findBySettledFalseOrderByPositionDateAscId()).thenReturn(List.of(p));

        var snap = service.snapshot();
        assertThat(snap.floats()).hasSize(1);
        assertThat(snap.floats().get(0).currency()).isEqualTo("NAD");
        assertThat(snap.floats().get(0).floatNetMinor()).isEqualTo("3000");
    }
}
