package com.fsp.idp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fsp.idp")
public final class IdpProperties {
    private String issuer = "https://idp.fsp.local";
    private long accessTokenTtlSeconds = 900;
    /** Refresh token lifetime — at most the BFF's absolute session (8 h); revoked earlier at logout. */
    private long refreshTokenTtlSeconds = 28800;
    private String signingKeyPath = "../infrastructure/pki/dev/idp-signing.pem";
    // Red-team P0 fix: NO in-code default — deny-all until the operator injects the secret
    // via env (FSP_INTERNAL_CREDENTIAL_IDP); only the gateway's /idp/policy hop uses it.
    private String internalCredential = "";
    /** Portal origin the reset mail points at (V30 auth-plane); SPA hosts the reset form. */
    private String resetUrlBase = "http://localhost:5173";

    /** V36: public origin (the gateway) the hosted activation page is reached at. */
    private String publicBaseUrl = "http://localhost:8090";

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String v) { this.publicBaseUrl = v; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public long getAccessTokenTtlSeconds() { return accessTokenTtlSeconds; }
    public long getRefreshTokenTtlSeconds() { return refreshTokenTtlSeconds; }
    public void setRefreshTokenTtlSeconds(long v) { this.refreshTokenTtlSeconds = v; }
    public void setAccessTokenTtlSeconds(long v) { this.accessTokenTtlSeconds = v; }
    public String getSigningKeyPath() { return signingKeyPath; }
    public void setSigningKeyPath(String p) { this.signingKeyPath = p; }
    public String getInternalCredential() { return internalCredential; }
    public void setInternalCredential(String c) { this.internalCredential = c; }
    public String getResetUrlBase() { return resetUrlBase; }
    public void setResetUrlBase(String v) { this.resetUrlBase = v; }
}
