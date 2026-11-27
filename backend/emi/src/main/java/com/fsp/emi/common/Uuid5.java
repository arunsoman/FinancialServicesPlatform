package com.fsp.emi.common;

import java.util.UUID;

/**
 * Deterministic UUIDv5 (namespace-style) derivation used for TigerBeetle ids so every
 * retry reproduces identical ids. EMI copy — services do not import core classes.
 */
public final class Uuid5 {

    /** Platform namespace — fixed for Phase-0; document before changing (breaks TB ids). */
    public static final UUID FSP_NAMESPACE = UUID.fromString("a0b1c2d3-e4f5-4a6b-8c7d-9e0f1a2b3c4d");

    private Uuid5() {}

    /** TB account id of the customer wallet (liability side of the EMI book). */
    public static UUID walletAccountId(UUID walletId) {
        return fromName("emi:w:" + walletId);
    }

    /** TB account holding the EMI float mirror (NAD; asset side). */
    public static UUID floatAccountId() {
        return fromName("emi:float:NAD");
    }

    /** Deterministic TB transfer id for one journal movement. */
    public static UUID journalTransferId(UUID journalId) {
        return fromName("emi:t:" + journalId);
    }

    // ---- Stage 3: bill-payment saga derivations (chapter 13 §5) ----------------

    /** TB account where held bill-payment funds park between hold and settlement. */
    public static UUID billClearingAccountId(String currency) {
        return fromName("emi:bill-clearing:" + currency);
    }

    /** The customer's PENDING (authorization-hold) transfer for one saga. */
    public static UUID sagaPendingTransferId(UUID sagaId) {
        return fromName("emi:saga:" + sagaId + ":hold");
    }

    /** The post-pending (commit) transfer resolving the hold on settlement. */
    public static UUID sagaCommitTransferId(UUID sagaId) {
        return fromName("emi:saga:" + sagaId + ":commit");
    }

    /** The void-pending transfer releasing the hold on rollback/expiry.
     */
    public static UUID sagaVoidTransferId(UUID sagaId) {
        return fromName("emi:saga:" + sagaId + ":void");
    }

    // ---- Stage 4: SKU order charges (plan §7.1) ------------------------------

    /** SKU clearing: held funds park here between hold and fulfilment confirm. */
    public static UUID skuClearingAccountId(String currency) {
        return fromName("emi:sku-clearing:" + currency);
    }

    /** Fees charged inside the wallet book (0 when the fee lives only in the core GL). */
    public static UUID feeClearingAccountId(String currency) {
        return fromName("emi:fee-clearing:" + currency);
    }

    /** VAT liability slice of a wallet-funded sale. */
    public static UUID taxLiabilityAccountId(String currency) {
        return fromName("emi:tax-liability:" + currency);
    }

    /** Deterministic pending transfer id for one linked-chain leg (0-based leg index). */
    public static UUID chargeLegTransferId(UUID chargeId, int leg) {
        return fromName("emi:charge:" + chargeId + ":hold:" + leg);
    }

    /** The post-pending (commit) transfer resolving one leg on fulfilment confirm. */
    public static UUID chargeCommitTransferId(UUID chargeId, int leg) {
        return fromName("emi:charge:" + chargeId + ":commit:" + leg);
    }

    /** The void-pending transfer releasing one leg on cancel/expiry. */
    public static UUID chargeVoidTransferId(UUID chargeId, int leg) {
        return fromName("emi:charge:" + chargeId + ":void:" + leg);
    }

    // Standard SHA-1 based UUIDv5
    public static UUID fromName(String name) {
        return fromNamespace(FSP_NAMESPACE, name);
    }

    public static UUID fromNamespace(UUID namespace, String name) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] nsBytes = toBytes(namespace);
            byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            md.update(nsBytes);
            md.update(nameBytes);
            byte[] hash = md.digest();
            hash[6] = (byte) ((hash[6] & 0b00001111) | 0b0101_0000); // version 5
            hash[8] = (byte) ((hash[8] & 0b0011_1111) | 0b1000_0000); // RFC4122 variant
            return UUID.nameUUIDFromBytes(java.util.Arrays.copyOf(hash, 16));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        return java.nio.ByteBuffer.allocate(16)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits())
            .array();
    }
}
