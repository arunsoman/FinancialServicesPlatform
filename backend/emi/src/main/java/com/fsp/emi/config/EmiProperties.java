package com.fsp.emi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** fsp.emi.* — chapter 13 Stage 2. */
@ConfigurationProperties(prefix = "fsp.emi")
public class EmiProperties {

    private final TigerBeetle tigerbeetle = new TigerBeetle();
    private final Caps caps = new Caps();
    private final Biller biller = new Biller();

    /** PSD-3 §9.1.1: regulated e-money balances are NAD-only, redeemable at par. */
    private String walletCurrency = "NAD";
    /** The one disposition knob (plan §5.3) — every saga deadline derives from this. */
    private long sagaGraceSeconds = 3024000; // 35 d
    /** Shared internal hop credential — proves a request came from the gateway (AGENTS §1.4). */
    // Red-team P0 fix: NO in-code default — deny-all until the operator injects the secret
    // via env (FSP_INTERNAL_CREDENTIAL_EMI).
    private String internalCredential = "";

    public TigerBeetle getTigerBeetle() { return tigerbeetle; }
    public Caps getCaps() { return caps; }
    public Biller getBiller() { return biller; }
    public String getWalletCurrency() { return walletCurrency; }
    public void setWalletCurrency(String v) { this.walletCurrency = v; }
    public long getSagaGraceSeconds() { return sagaGraceSeconds; }
    public void setSagaGraceSeconds(long v) { this.sagaGraceSeconds = v; }
    public String getInternalCredential() { return internalCredential; }
    public void setInternalCredential(String v) { this.internalCredential = v; }

    public static class TigerBeetle {
        private int clusterId = 1;
        private String addresses = "127.0.0.1:3000";
        /** EMI book ledger id (chapter 13 §1). */
        private int bookId = 806;

        public int getClusterId() { return clusterId; }
        public void setClusterId(int v) { this.clusterId = v; }
        public String getAddresses() { return addresses; }
        public void setAddresses(String v) { this.addresses = v; }
        public int getBookId() { return bookId; }
        public void setBookId(int v) { this.bookId = v; }
    }

    /** PSD-3 micro e-money envelope: P2P/P2B only, capped float (GN 561/2024). */
    public static class Caps {
        /** Daily redemption cap per wallet (NAD minor units; default N$5,000). */
        private long dailyRedeemMinor = 500000;
        /** Max bookable float on one wallet movement guard (N$1,000,000 × 100). */
        private long maxMovementMinor = 100000000;

        public long getDailyRedeemMinor() { return dailyRedeemMinor; }
        public void setDailyRedeemMinor(long v) { this.dailyRedeemMinor = v; }
        public long getMaxMovementMinor() { return maxMovementMinor; }
        public void setMaxMovementMinor(long v) { this.maxMovementMinor = v; }
    }

    /** Biller REST rail tuning (chapter 13 Stage 3 — protocol support). */
    public static class Biller {
        /** Bulkhead width (Stage-1 RailGuard Parallel capability) per biller connector. */
        private int parallelism = 4;
        /** HTTP call timeout for biller submit/probe calls. */
        private long timeoutMs = 5000;

        public int getParallelism() { return parallelism; }
        public void setParallelism(int v) { this.parallelism = v; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long v) { this.timeoutMs = v; }
    }
}
