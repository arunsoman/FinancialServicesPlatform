package com.fsp.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * recon-worker invariant loop (Stage 6 — LANDED; chapter 13 §8, plan §11):
 *
 * <ol>
 *   <li><b>Cheap PSP sweep</b> every interval: clearing-zero per closed cycle, PSD-1
 *       pay-over, stale captures (reads the PSP internal recon face).</li>
 *   <li><b>EMI custody deep probe</b> daily (and once at boot): float net == wallet
 *       liability sum (plan §1.2, PSD-3) via the EMI internal recon face.</li>
 *   <li><b>Page-on-violation</b>: P0 proves flow into core's machine-plane
 *       {@code POST /v1/recon/pages} → notification rows → V29/V31 channel dispatch.
 *       Dedupe: each violation key pages at most once per {@code pageDedup} window.</li>
 * </ol>
 *
 * <p>Probe failures (feed down, snapshot missing) are recon warnings — never pages. The
 * worker never holds locks, never writes any book, and never blocks on I/O inside a
 * transaction (there is no transaction at all — it is stateless by construction).</p>
 */
@Component
public class ReconInvariantJob {

    private static final Logger log = LoggerFactory.getLogger(ReconInvariantJob.class);

    private final ReconGateway gateway;
    private final ReconProperties props;
    /** Violation key → last page dispatch; keeps the same break from paging 24×24/day. */
    private final Map<String, Instant> lastPaged = new ConcurrentHashMap<>();
    private volatile LocalDate lastDeepRun = LocalDate.MIN;
    private volatile boolean bootRan = false;

    public ReconInvariantJob(ReconGateway gateway, ReconProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${fsp.recon.sweep-interval-ms:60000}")
    public void sweep() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<ReconFeeds.Violation> violations = new java.util.ArrayList<>(pspChecks(today));

        // EMI custody deep probe: daily + once at boot (cheap PSP loop stays per-interval).
        if (!bootRan || today.isAfter(lastDeepRun)) {
            violations.addAll(emiChecks());
            lastDeepRun = today;
            bootRan = true;
        }

        for (ReconFeeds.Violation v : violations) {
            handleViolation(v);
        }
    }

    /** Visible for tests: PSP invariants over one snapshot. */
    List<ReconFeeds.Violation> pspChecks(LocalDate today) {
        var snapOpt = gateway.fetchPspSnapshot();
        if (snapOpt.isEmpty()) {
            log.warn("recon_probe_failed feed=psp (down or malformed) — no pages this pass");
            return List.of();
        }
        ReconFeeds.PspSnapshot snap = snapOpt.get();
        List<ReconFeeds.Violation> out = new java.util.ArrayList<>();
        out.addAll(ReconInvariants.pspClearingZero(snap, today));
        out.addAll(ReconInvariants.pspPayOver(snap));
        out.addAll(ReconInvariants.pspStaleCaptures(snap));
        return out;
    }

    /** Visible for tests: EMI custody invariant over one snapshot. */
    List<ReconFeeds.Violation> emiChecks() {
        var snapOpt = gateway.fetchEmiSnapshot();
        if (snapOpt.isEmpty()) {
            log.warn("recon_probe_failed feed=emi (down or malformed) — no pages this pass");
            return List.of();
        }
        return ReconInvariants.emiCustody(snapOpt.get());
    }

    /** Visible for tests. */
    void handleViolation(ReconFeeds.Violation v) {
        log.error("recon_violation invariant={} severity={} key={}: {}", v.invariant(),
            v.severity(), v.key(), v.detail());
        Instant now = Instant.now();
        Instant prev = lastPaged.get(v.key());
        if (prev != null && prev.plus(props.getPageDedup()).isAfter(now)) {
            log.warn("recon_page_suppressed key={} lastPaged={} dedup={}", v.key(), prev,
                props.getPageDedup());
            return;
        }
        if (gateway.page(v.invariant(), v.severity(), v.detail())) {
            lastPaged.put(v.key(), now);
            log.info("recon_page_sent invariant={} severity={} key={}", v.invariant(),
                v.severity(), v.key());
        } else {
            log.warn("recon_page_failed key={} (core unreachable) — retried next sweep", v.key());
        }
    }

}
