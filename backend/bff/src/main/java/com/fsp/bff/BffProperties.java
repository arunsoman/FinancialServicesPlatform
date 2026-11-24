package com.fsp.bff;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** BFF wiring (FAPI2 guide §1: confidential client keeps tokens server-side; ALL traffic via the gateway). */
@ConfigurationProperties(prefix = "fsp")
public class BffProperties {

    private final Gateway gateway = new Gateway();
    private final Proxy proxy = new Proxy();
    private final Session session = new Session();
    private String idpIssuer = "https://idp.fsp.local";
    private String loginRedirectUri = "http://localhost:5173/bff/auth/callback";
    /** Public base URL the BROWSER is sent to for /idp/authorize (defaults to gateway baseUrl;
     *  in deployments where the gateway is loopback-only, point this at the IdP's public origin). */
    private String authorizeUrlBase;

    public String getAuthorizeUrlBase() {
        return authorizeUrlBase != null && !authorizeUrlBase.isBlank() ? authorizeUrlBase : gateway.baseUrl;
    }
    public void setAuthorizeUrlBase(String v) { this.authorizeUrlBase = v; }

    public Gateway getGateway() { return gateway; }
    public Proxy getProxy() { return proxy; }
    public Session getSession() { return session; }
    public String getIdpIssuer() { return idpIssuer; }
    public void setIdpIssuer(String v) { this.idpIssuer = v; }
    public String getLoginRedirectUri() { return loginRedirectUri; }
    public void setLoginRedirectUri(String v) { this.loginRedirectUri = v; }

    /** Red-team P1 fix: browser origins allowed to make state-changing BFF calls. */
    private String allowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";

    public String getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(String v) { this.allowedOrigins = v; }

    /** Single upstream: the API gateway routes /idp/** to the IdP and /v1/** to core. */
    /**
     * Session lifetime (FAPI 2.0 BFF): the server drops a session after {@code idleTimeoutMinutes}
     * without any request and, whatever happens, {@code absoluteTimeoutMinutes} after sign-in. The
     * browser signs out after the same idle time measured on real user activity (polling doesn't
     * count) — /bff/session tells it the number.
     */
    public static class Session {
        private int idleTimeoutMinutes = 30;
        private int absoluteTimeoutMinutes = 480;
        /** renew the access token when it has less than this left */
        private int refreshSkewSeconds = 60;

        public int getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
        public void setIdleTimeoutMinutes(int v) { this.idleTimeoutMinutes = v; }
        public int getAbsoluteTimeoutMinutes() { return absoluteTimeoutMinutes; }
        public void setAbsoluteTimeoutMinutes(int v) { this.absoluteTimeoutMinutes = v; }
        public int getRefreshSkewSeconds() { return refreshSkewSeconds; }
        public void setRefreshSkewSeconds(int v) { this.refreshSkewSeconds = v; }
    }

    public static class Gateway {
        private String baseUrl = "http://localhost:8090";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
    }

    public static class Proxy {
        /** Guide §6: bounded bodies at the gateway (journal.approve example uses 16 KB). */
        private int maxBodyBytes = 65_536;

        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int v) { this.maxBodyBytes = v; }
    }
}