package com.fsp.emi.recon;

import java.time.Instant;
import java.util.List;

/**
 * Read-only recon face (chapter 13 §8, plan Stage 6): the EMI service publishes what the
 * recon-worker needs to assert the customer-custody invariant (plan §1.2 / PSD-3) — per
 * currency the TigerBeetle wallet-account sum (Σ credits−debits, the authoritative record)
 * and the float account net (the asset-side mirror). Served on the INTERNAL PLANE ONLY
 * ({@code /internal/recon/snapshot}; internal-credential hop, never routed by the gateway,
 * never in the operation catalog). Money is STRING on the wire (platform AGENTS §2b).
 */
public final class EmiReconSnapshot {

    /**
     * One custody line (§1.2/PSD-3, chapter 13 §8): floatNetMinor must equal
     * walletSumMinor + heldSumMinor — held funds (bill/SKU/fee/tax clearing) left the
     * wallets but are still physically in the float until externally settled.
     * floatNetMinor is null when the float account cannot be proved (unknown ≠ zero).
     */
    public record CustodyLine(String currency, long walletCount, String walletSumMinor,
                              String heldSumMinor, String floatNetMinor, int walletsUnproven) {}

    public record Snapshot(Instant generatedAt, List<CustodyLine> custody) {}
}
