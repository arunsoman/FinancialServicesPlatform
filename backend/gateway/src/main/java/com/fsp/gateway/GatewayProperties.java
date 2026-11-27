package com.fsp.gateway;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fsp.gateway")
public final class GatewayProperties {
    private String coreBaseUrl = "http://localhost:8081";
    private String idpBaseUrl = "http://localhost:8082";
    /** EMI service internal face (:8086) — routes derived from its /internal/ops/catalog. */
    private String emiBaseUrl = "http://localhost:8086";
    /** PSP service internal face (:8085) — routes derived from its /internal/ops/catalog. */
    private String pspBaseUrl = "http://localhost:8085";
    private String issuer = "https://idp.fsp.local";
    private String audience = "fsp-financial-api";
    /** Outbound hop to core only — red-team P0 fix: per-target secrets (this is core's),
     *  NO in-code default; unset = gateway cannot open the internal plane. */
    private String internalCredential = "";
    /** Outbound hops to the book-owner services (per-target credentials, red-team P0 fix). */
    private String emiInternalCredential = "";
    private String pspInternalCredential = "";
    /** Outbound hop to the IdP policy surface. */
    private String idpInternalCredential = "";

    public String getEmiInternalCredential() { return emiInternalCredential; }
    public void setEmiInternalCredential(String v) { this.emiInternalCredential = v; }
    public String getPspInternalCredential() { return pspInternalCredential; }
    public void setPspInternalCredential(String v) { this.pspInternalCredential = v; }
    public String getIdpInternalCredential() { return idpInternalCredential; }
    public void setIdpInternalCredential(String v) { this.idpInternalCredential = v; }
    private int catalogRefreshSeconds = 60;
    private long maxBodyBytes = 1048576;
    /** PKCS#8 PEM with the P-256 key that signs actor assertions (dev: created on first boot). */
    private String assertionKey = "../../infrastructure/pki/dev/gateway-assertion.pem";
    private boolean assertionKeyCreate = true;

    /** Per-client (browser) rate limit at the gateway edge. Defense-in-depth only —
     *  FAPI 2.0 does not prescribe inter-request spacing (see BrowserRateLimiter). */
    private RateLimit rateLimit = new RateLimit(true, 1, Duration.ofMillis(250));

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit v) { this.rateLimit = v; }

    public record RateLimit(boolean enabled, int maxRequests, Duration window) {
        public RateLimit { }
    }

    public String getCoreBaseUrl() { return coreBaseUrl; }
    public void setCoreBaseUrl(String v) { this.coreBaseUrl = v; }
    public String getEmiBaseUrl() { return emiBaseUrl; }
    public void setEmiBaseUrl(String v) { this.emiBaseUrl = v; }
    public String getPspBaseUrl() { return pspBaseUrl; }
    public void setPspBaseUrl(String v) { this.pspBaseUrl = v; }
    public String getIdpBaseUrl() { return idpBaseUrl; }
    public void setIdpBaseUrl(String v) { this.idpBaseUrl = v; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String v) { this.issuer = v; }
    public String getAudience() { return audience; }
    public void setAudience(String v) { this.audience = v; }
    public String getInternalCredential() { return internalCredential; }
    public void setInternalCredential(String v) { this.internalCredential = v; }
    public int getCatalogRefreshSeconds() { return catalogRefreshSeconds; }
    public void setCatalogRefreshSeconds(int v) { this.catalogRefreshSeconds = v; }
    public long getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(long v) { this.maxBodyBytes = v; }
    public String getAssertionKey() { return assertionKey; }
    public void setAssertionKey(String v) { this.assertionKey = v; }
    public boolean isAssertionKeyCreate() { return assertionKeyCreate; }
    public void setAssertionKeyCreate(boolean v) { this.assertionKeyCreate = v; }
}
