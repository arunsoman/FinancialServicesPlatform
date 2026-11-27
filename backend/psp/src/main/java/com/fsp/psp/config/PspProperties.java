package com.fsp.psp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** PSP service settings (fsp.psp.*). */
@ConfigurationProperties(prefix = "fsp.psp")
public class PspProperties {

    private TigerBeetle tigerbeetle = new TigerBeetle();
    private Settlement settlement = new Settlement();
    private Acquire acquire = new Acquire();
    // Red-team P0 fix: NO in-code default — deny-all until the operator injects the secret
    // via env (FSP_INTERNAL_CREDENTIAL_PSP).
    private String internalCredential = "";
    private Outbox outbox = new Outbox();

    public TigerBeetle getTigerbeetle() { return tigerbeetle; }
    public void setTigerbeetle(TigerBeetle v) { this.tigerbeetle = v; }
    public Settlement getSettlement() { return settlement; }
    public void setSettlement(Settlement v) { this.settlement = v; }
    public Acquire getAcquire() { return acquire; }
    public void setAcquire(Acquire v) { this.acquire = v; }
    public String getInternalCredential() { return internalCredential; }
    public void setInternalCredential(String v) { this.internalCredential = v; }
    public Outbox getOutbox() { return outbox; }
    public void setOutbox(Outbox v) { this.outbox = v; }

    public static class TigerBeetle {
        private String addresses = "127.0.0.1:3000";
        private int clusterId = 0;
        /** PSP book — nominal clearing positions live here and MUST be zero each cycle. */
        private int bookId = 805;

        public String getAddresses() { return addresses; }
        public void setAddresses(String v) { this.addresses = v; }
        public int getClusterId() { return clusterId; }
        public void setClusterId(int v) { this.clusterId = v; }
        public int getBookId() { return bookId; }
        public void setBookId(int v) { this.bookId = v; }
    }

    public static class Settlement {
        /** PSD-1 §12.2.3: merchant pay-over within 2 business days — our sweep beats it. */
        private int payOverDays = 2;
        /** Two-phase capture pending timeout: how long an unswept capture may stay pending. */
        private long settleGraceSeconds = Duration.ofDays(2).toSeconds();
        /** TTL sweeper cadence: sweeps due settlement cycles (yesterday and older). */
        private long sweepIntervalMs = 60000;
        private long sweepInitialDelayMs = 30000;
        /** Max acquisitions committed in one sweep cycle pass (bounded paging). */
        private int sweepBatchSize = 200;

        public int getPayOverDays() { return payOverDays; }
        public void setPayOverDays(int v) { this.payOverDays = v; }
        public long getSettleGraceSeconds() { return settleGraceSeconds; }
        public void setSettleGraceSeconds(long v) { this.settleGraceSeconds = v; }
        public long getSweepIntervalMs() { return sweepIntervalMs; }
        public void setSweepIntervalMs(long v) { this.sweepIntervalMs = v; }
        public long getSweepInitialDelayMs() { return sweepInitialDelayMs; }
        public void setSweepInitialDelayMs(long v) { this.sweepInitialDelayMs = v; }
        public int getSweepBatchSize() { return sweepBatchSize; }
        public void setSweepBatchSize(int v) { this.sweepBatchSize = v; }
    }

    public static class Acquire {
        /** Single-movement exposure cap (plan §3.4.3 scheme limits are advisory until conformed). */
        private long maxMovementMinor = 100_000_000L;
        /** Phase-0 rails settle NAD only (chapter 13: AFRIQPAY scheme currency). */
        private List<String> supportedCurrencies = new ArrayList<>(List.of("NAD"));

        public long getMaxMovementMinor() { return maxMovementMinor; }
        public void setMaxMovementMinor(long v) { this.maxMovementMinor = v; }
        public List<String> getSupportedCurrencies() { return supportedCurrencies; }
        public void setSupportedCurrencies(List<String> v) { this.supportedCurrencies = v; }
    }

    public static class Outbox {
        private long sweepInterval = 500;

        public long getSweepInterval() { return sweepInterval; }
        public void setSweepInterval(long v) { this.sweepInterval = v; }
    }
}
