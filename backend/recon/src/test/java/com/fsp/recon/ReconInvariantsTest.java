package com.fsp.recon;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 6 invariant math (chapter 13 §8) — pure, no I/O. Doctrine: only PROVEN breaks
 * page; unparseable money / missing fields / still-open cycles are filtered out, never
 * defaulted to zero.
 */
class ReconInvariantsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    private static ReconFeeds.PspPosition position(String connector, String ccy,
                                                   String date, String amount, boolean settled,
                                                   String tbNet) {
        return new ReconFeeds.PspPosition(connector, ccy, date, amount, settled, tbNet);
    }

    @Test
    void closedCycleWithResidualIsP0() {
        var snap = new ReconFeeds.PspSnapshot("t", List.of(
            position("DEMO-BANK", "NAD", "2026-11-21", "3000", false, "0"),
            position("DEMO-BANK", "NAD", "2026-11-20", "0", true, "0"),
            position("OTHER", "NAD", "2026-11-21", "0", true, "500")), List.of(), 0, List.of());
        var out = ReconInvariants.pspClearingZero(snap, TODAY);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).invariant()).isEqualTo("PSP_CLEARING_RESIDUAL");
        assertThat(out.get(0).severity()).isEqualTo("P0");
        assertThat(out.get(0).key()).isEqualTo("psp-clearing:DEMO-BANK:NAD:2026-11-21");
        assertThat(out.get(1).key()).isEqualTo("psp-clearing:OTHER:NAD:2026-11-21");
    }

    @Test
    void todayPendingCycleIsNotAViolation() {
        var snap = new ReconFeeds.PspSnapshot("t", List.of(
            position("DEMO-BANK", "NAD", "2026-11-22", "3000", false, "-3000")), List.of(), 0, List.of());
        assertThat(ReconInvariants.pspClearingZero(snap, TODAY)).isEmpty();
    }

    @Test
    void malformedMoneyNeverPages() {
        var snap = new ReconFeeds.PspSnapshot("t", List.of(
            position("DEMO-BANK", "NAD", "2026-11-21", "not-a-number", false, "0"),
            position("DEMO-BANK", "NAD", "2026-11-21", null, false, "0")), List.of(), 0, List.of());
        assertThat(ReconInvariants.pspClearingZero(snap, TODAY)).isEmpty();
    }

    @Test
    void missingDateRowIsSkipped() {
        var snap = new ReconFeeds.PspSnapshot("t", List.of(
            position("DEMO-BANK", "NAD", null, "5000", false, "0")), List.of(), 0, List.of());
        assertThat(ReconInvariants.pspClearingZero(snap, TODAY)).isEmpty();
    }

    @Test
    void payOverAndStaleCapturesPageP0() {
        var snap = new ReconFeeds.PspSnapshot("t", List.of(), List.of(
            new ReconFeeds.PspPayableOverdue(UUID_FOR_TEST, "NAD", "2026-11-10",
                "2700", "0", 6)), 3, List.of());
        var out = ReconInvariants.pspPayOver(snap);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).invariant()).isEqualTo("PSP_PAY_OVER_BREACH");
        assertThat(out.get(0).severity()).isEqualTo("P0");
        var stale = ReconInvariants.pspStaleCaptures(snap);
        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).invariant()).isEqualTo("PSP_CAPTURE_STALE");
        assertThat(ReconInvariants.pspStaleCaptures(
            new ReconFeeds.PspSnapshot("t", List.of(), List.of(), 0, List.of()))).isEmpty();
    }

    @Test
    void custodyShortfallIsP0AndBalancedBookIsSilent() {
        // float 72500 vs wallets 47385 + held 25115 = 72500 → backed, silent
        var balanced = new ReconFeeds.EmiSnapshot("t", List.of(
            new ReconFeeds.EmiCustodyLine("NAD", 6, "47385", "25115", "72500", 0)));
        assertThat(ReconInvariants.emiCustody(balanced)).isEmpty();

        // held money ignored → delta = -25115 → P0
        var broken = new ReconFeeds.EmiSnapshot("t", List.of(
            new ReconFeeds.EmiCustodyLine("NAD", 6, "47385", "25115", "72505", 0)));
        var out = ReconInvariants.emiCustody(broken);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).invariant()).isEqualTo("EMI_FLOAT_SHORTFALL");
        assertThat(out.get(0).severity()).isEqualTo("P0");
        assertThat(out.get(0).detail()).contains("delta=5");

        // unprovable float → probe noise (never pages, never defaults to zero)
        var unprovable = new ReconFeeds.EmiSnapshot("t", List.of(
            new ReconFeeds.EmiCustodyLine("NAD", 6, "47385", "25115", null, 0)));
        assertThat(ReconInvariants.emiCustody(unprovable)).isEmpty();
    }

    @Test
    void oversizeMoneyAndBlankStringsAreProbeNoise() {
        var snap = new ReconFeeds.PspSnapshot("t", List.of(
            position("X", "NAD", "2026-11-21", "922337203685477580700", false, "0"),
            position("X", "NAD", "2026-11-21", "  ", false, "0")), List.of(), 0, List.of());
        assertThat(ReconInvariants.pspClearingZero(snap, TODAY)).isEmpty();
    }

    private static final java.util.UUID UUID_FOR_TEST =
        java.util.UUID.fromString("11111111-2222-4333-8444-555555555555");
}
