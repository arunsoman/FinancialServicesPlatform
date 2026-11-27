package com.fsp.recon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * recon-worker job wiring (Stage 6): probe failure pages nothing, proven violations page
 * exactly once per dedupe window via the core machine-plane op, suppressed repeats are
 * logged and skipped, core-outage retries on the next sweep with no dedupe burn.
 */
class ReconInvariantJobTest {

    ReconGateway gateway;
    ReconInvariantJob job;
    ReconProperties props;

    static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    static ReconFeeds.Violation violation(String key) {
        return new ReconFeeds.Violation("PSP_CLEARING_RESIDUAL", "P0", key, "detail");
    }

    @BeforeEach
    void setUp() {
        gateway = mock(ReconGateway.class);
        props = new ReconProperties();
        props.setPageDedup(Duration.ofHours(24));
        job = spy(new ReconInvariantJob(gateway, props));
    }

    @Test
    void pspProbeFailurePagesNothing() {
        when(gateway.fetchPspSnapshot()).thenReturn(Optional.empty());
        assertThat(job.pspChecks(TODAY)).isEmpty();
        verify(gateway, never()).page(anyString(), anyString(), anyString());
    }

    @Test
    void violationPagesOnceThenDedupSuppresses() {
        doReturn(true).when(gateway).page(anyString(), anyString(), anyString());
        job.handleViolation(violation("k1"));
        job.handleViolation(violation("k1")); // same key, inside window → suppressed
        verify(gateway, org.mockito.Mockito.times(1)).page(
            org.mockito.ArgumentMatchers.eq("PSP_CLEARING_RESIDUAL"),
            org.mockito.ArgumentMatchers.eq("P0"),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void distinctKeysPageIndependently() {
        doReturn(true).when(gateway).page(anyString(), anyString(), anyString());
        job.handleViolation(violation("a"));
        job.handleViolation(violation("b"));
        verify(gateway, org.mockito.Mockito.times(2)).page(anyString(), anyString(), anyString());
    }

    @Test
    void coreOutageDoesNotBurnTheDedup() {
        doReturn(false).when(gateway).page(anyString(), anyString(), anyString());
        job.handleViolation(violation("k9"));
        doReturn(true).when(gateway).page(anyString(), anyString(), anyString());
        job.handleViolation(violation("k9")); // retry, because the first page never landed
        verify(gateway, org.mockito.Mockito.times(2)).page(anyString(), anyString(), anyString());
    }

    @Test
    void fullSweepRunsPspChecksAndHandsViolationsToThePager() {
        ReconFeeds.PspSnapshot snap = new ReconFeeds.PspSnapshot("t", List.of(
            new ReconFeeds.PspPosition("DEMO-BANK", "NAD", "2026-11-21", "5000",
                false, "-2000")), List.of(), 0, List.of());
        when(gateway.fetchPspSnapshot()).thenReturn(Optional.of(snap));
        when(gateway.fetchEmiSnapshot()).thenReturn(Optional.empty()); // deep probe down → warn only
        doReturn(true).when(gateway).page(anyString(), anyString(), anyString());

        job.sweep(); // boot pass: emi probe attempted + psp violations page

        verify(gateway).page(org.mockito.ArgumentMatchers.eq("PSP_CLEARING_RESIDUAL"),
            org.mockito.ArgumentMatchers.eq("P0"), org.mockito.ArgumentMatchers.contains("DEMO-BANK"));

        // Second sweep the same day: the violation is still found and re-logged, but the
        // dedupe window suppresses the repeat page — and the deep probe is daily.
        org.mockito.Mockito.reset(gateway);
        when(gateway.fetchPspSnapshot()).thenReturn(Optional.of(snap));
        doReturn(true).when(gateway).page(anyString(), anyString(), anyString());
        job.sweep();
        verify(gateway, never()).page(anyString(), anyString(), anyString());
    }

    @Test
    void emiCustodyViolationPagesWhenFeedProvesBreak() {
        when(gateway.fetchPspSnapshot()).thenReturn(Optional.empty());
        ReconFeeds.EmiSnapshot emi = new ReconFeeds.EmiSnapshot("t", List.of(
            new ReconFeeds.EmiCustodyLine("NAD", 2, "4700", "0", "4703", 0)));
        when(gateway.fetchEmiSnapshot()).thenReturn(Optional.of(emi));
        doReturn(true).when(gateway).page(anyString(), anyString(), anyString());

        job.sweep();

        verify(gateway).page(org.mockito.ArgumentMatchers.eq("EMI_FLOAT_SHORTFALL"),
            org.mockito.ArgumentMatchers.eq("P0"), anyString());
    }
}
