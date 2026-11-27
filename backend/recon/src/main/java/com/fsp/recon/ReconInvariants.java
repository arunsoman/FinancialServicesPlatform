package com.fsp.recon;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure invariant calculator (plan Stage 6, chapter 13 §8): maps the honest read-only
 * snapshots from each book-owner service onto violations. No I/O, no clock access —
 * everything flows in as arguments so the invariants are unit-testable in isolation.
 *
 * <p>Doctrine: probe failures (feed down, transport error, unparseable money) never page —
 * a recon outage must not page the platform into numbness. Only PROVEN invariant breaks
 * page. The daily-cadence "coverage miss" (a feed that never came back) is surfaced as a
 * recon warning log, not a P0.</p>
 */
public final class ReconInvariants {

    private ReconInvariants() {}

    /** PSP clearing == 0 per CLOSED cycle (plan §1.1, chapter 13 §8). P0 — always. */
    public static List<ReconFeeds.Violation> pspClearingZero(ReconFeeds.PspSnapshot snap,
                                                             LocalDate todayUtc) {
        List<ReconFeeds.Violation> out = new ArrayList<>();
        if (snap == null || snap.positions() == null) return out;
        for (ReconFeeds.PspPosition p : snap.positions()) {
            LocalDate positionDate = parseDate(p.positionDate());
            if (positionDate == null || !positionDate.isBefore(todayUtc)) {
                continue; // the cycle is still open today — not a violation
            }
            Long amount = parseMoney(p.amountMinor(), positionDate);
            Long tbNet = parseMoney(p.tbNet(), positionDate);
            if (amount == null || tbNet == null) continue; // unparseable → not a proven break
            if (!p.settled() || amount != 0L || tbNet != 0L) {
                out.add(new ReconFeeds.Violation("PSP_CLEARING_RESIDUAL", "P0",
                    "psp-clearing:%s:%s:%s".formatted(p.connectorCode(), p.currency(), p.positionDate()),
                    ("clearing not zero for settled-or-past cycle: connector=%s currency=%s cycle=%s "
                        + "position=%d tbNet=%d settled=%s")
                        .formatted(p.connectorCode(), p.currency(), p.positionDate(), amount, tbNet, p.settled())));
            }
        }
        return out;
    }

    /** PSD-1 §12.2.3 pay-over: no outstanding payable past the merchant's ceiling. P0. */
    public static List<ReconFeeds.Violation> pspPayOver(ReconFeeds.PspSnapshot snap) {
        List<ReconFeeds.Violation> out = new ArrayList<>();
        if (snap == null || snap.payablesOverdue() == null) return out;
        for (ReconFeeds.PspPayableOverdue p : snap.payablesOverdue()) {
            out.add(new ReconFeeds.Violation("PSP_PAY_OVER_BREACH", "P0",
                "psp-payover:%s:%s:%s".formatted(p.merchantId(), p.currency(), p.periodStart()),
                ("merchant payable overdue (PSD-1 §12.2.3): merchant=%s currency=%s period=%s "
                    + "accrual=%s paid=%s daysOver=%d")
                    .formatted(p.merchantId(), p.currency(), p.periodStart(),
                        p.accrualMinor(), p.paidMinor(), p.daysOver())));
        }
        return out;
    }

    /** Captures pending past the settle grace that were never swept. P0. */
    public static List<ReconFeeds.Violation> pspStaleCaptures(ReconFeeds.PspSnapshot snap) {
        List<ReconFeeds.Violation> out = new ArrayList<>();
        if (snap == null || snap.staleCaptures() <= 0) return out;
        out.add(new ReconFeeds.Violation("PSP_CAPTURE_STALE", "P0", "psp-stale-captures",
            "captures pending past the settle grace: count=%d".formatted(snap.staleCaptures())));
        return out;
    }

    /**
     * EMI customer custody (plan §1.2, PSD-3): float net == wallet liability sum + held
     * funds (bill/SKU/fee/tax clearing — money still physically in the float). P0.
     */
    public static List<ReconFeeds.Violation> emiCustody(ReconFeeds.EmiSnapshot snap) {
        List<ReconFeeds.Violation> out = new ArrayList<>();
        if (snap == null || snap.custody() == null) return out;
        for (ReconFeeds.EmiCustodyLine c : snap.custody()) {
            Long walletSum = parseMoney(c.walletSumMinor(), null);
            Long heldSum = parseMoney(c.heldSumMinor(), null);
            Long floatNet = parseMoney(c.floatNetMinor(), null);
            if (walletSum == null || heldSum == null || floatNet == null) continue;
            long backed = walletSum.longValue() + heldSum.longValue();
            if (floatNet.longValue() != backed) {
                out.add(new ReconFeeds.Violation("EMI_FLOAT_SHORTFALL", "P0",
                    "emi-custody:" + c.currency(),
                    ("custody impaired (PSD-3): currency=%s wallets=%d walletSum=%d heldSum=%d "
                        + "backed=%d floatNet=%d delta=%d walletsUnproven=%d")
                        .formatted(c.currency(), c.walletCount(), walletSum, heldSum, backed,
                            floatNet, floatNet.longValue() - backed, c.walletsUnproven())));
            }
        }
        return out;
    }

    static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.length() > 10 ? iso.substring(0, 10) : iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Defensive wire parse: money is always string; null on any mismatch (never 0). */
    static Long parseMoney(String s, LocalDate dayForKey) {
        if (s == null || s.isBlank()) return null;
        try {
            BigInteger v = new BigInteger(s.strip());
            if (v.bitLength() > 63) return null;
            return v.longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
