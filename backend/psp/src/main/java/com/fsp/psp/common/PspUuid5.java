package com.fsp.psp.common;

import java.util.UUID;

/**
 * Deterministic UUIDv5 (namespace-style) derivation used for TigerBeetle ids so every
 * retry reproduces identical ids. PSP copy — services do not import core classes.
 *
 * <p>PSP book (805) account derivations (plan §1.1 / §4):</p>
 * <ul>
 *   <li>{@code psp:settlement-float:&lt;ccy&gt;} — asset mirror of the settlement bank account
 *       (rail money lands here).</li>
 *   <li>{@code psp:clearing:&lt;connector&gt;:&lt;ccy&gt;} — nominal clearing, MUST be zero at
 *       each settlement cycle (hard invariant; recon pages otherwise).</li>
 *   <li>{@code psp:merchant-payable:&lt;merchant&gt;:&lt;ccy&gt;} — liability owed to the
 *       merchant (pay-over ≤ payOverDays).</li>
 *   <li>{@code psp:fee-revenue:&lt;ccy&gt;} — acquiring fee revenue.</li>
 * </ul>
 */
public final class PspUuid5 {

    /** Platform namespace — fixed for Phase-0; document before changing (breaks TB ids). */
    public static final UUID FSP_NAMESPACE = UUID.fromString("a0b1c2d3-e4f5-4a6b-8c7d-9e0f1a2b3c4d");

    private PspUuid5() {}

    // ---- accounts ---------------------------------------------------------------

    /** Asset mirror of the settlement bank account where rail money lands. */
    public static UUID settlementFloatAccountId(String currency) {
        return fromName("psp:settlement-float:" + currency);
    }

    /** Nominal clearing account for one connector+currency — zero at cycle close. */
    public static UUID clearingAccountId(String connectorCode, String currency) {
        return fromName("psp:clearing:" + connectorCode + ":" + currency);
    }

    /** Merchant payable liability (credited on closing sweep, debited on payout). */
    public static UUID merchantPayableAccountId(UUID merchantId, String currency) {
        return fromName("psp:merchant-payable:" + merchantId + ":" + currency);
    }

    /** PSP acquiring-fee revenue account. */
    public static UUID feeRevenueAccountId(String currency) {
        return fromName("psp:fee-revenue:" + currency);
    }

    // ---- transfers ---------------------------------------------------------------

    /** Capture leg i of one acquisition: DR float gross → CR clearing gross, then fee extraction. */
    public static UUID captureLegTransferId(UUID acquisitionId, int leg) {
        return fromName("psp:acquire:" + acquisitionId + ":capture:" + leg);
    }

    /** Commit (post-pending) of capture leg i at settlement sweep. */
    public static UUID commitLegTransferId(UUID acquisitionId, int leg) {
        return fromName("psp:acquire:" + acquisitionId + ":commit:" + leg);
    }

    /** Void-pending of capture leg i (pre-sweep rollback). */
    public static UUID voidLegTransferId(UUID acquisitionId, int leg) {
        return fromName("psp:acquire:" + acquisitionId + ":void:" + leg);
    }

    /**
     * {@code closing_debit} (plan §1.1): DR clearing net → CR merchant payable at the
     * settlement sweep — the movement that drains the clearing account back to zero.
     */
    public static UUID closingDebitTransferId(UUID acquisitionId) {
        return fromName("psp:acquire:" + acquisitionId + ":close");
    }

    /** Deterministic manual-repair transfer id: distinct from the sweep's closing debit
     *  (TB marks failed ids as IdAlreadyFailed forever, so repair re-runs are safe). */
    public static UUID repairClosingDebitTransferId(UUID acquisitionId, long attempt) {
        return fromName("psp:acquire:" + acquisitionId + ":repair:" + attempt);
    }

    private static UUID fromName(String name) {
        return UUID.nameUUIDFromBytes((FSP_NAMESPACE + ":" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
