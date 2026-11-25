package com.fsp.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fsp")
public class FspProperties {

    private final Security security = new Security();
    private final TigerBeetle tigerbeetle = new TigerBeetle();
    private final Journal journal = new Journal();
    private final Push push = new Push();
    private final Email email = new Email();
    private final Sms sms = new Sms();

    public Security getSecurity() { return security; }
    public TigerBeetle getTigerBeetle() { return tigerbeetle; }
    public Journal getJournal() { return journal; }
    public Push getPush() { return push; }
    public Email getEmail() { return email; }
    public Sms getSms() { return sms; }

    public static class Security {
        // Red-team P0 fix: NO in-code default. The internal hop is closed (deny-all) until the
        // operator injects this service's secret via env (FSP_INTERNAL_CREDENTIAL for core).
        // Callers (gateway, recon-worker) each carry a per-target secret; see AGENTS §1.4.
        private String internalCredential = "";
        /** Outbound hop core→EMI carries EMI's secret (per-target credentials). */
        private String emiInternalCredential = "";

        public String getInternalCredential() { return internalCredential; }
        public void setInternalCredential(String v) { this.internalCredential = v; }
        public String getEmiInternalCredential() { return emiInternalCredential; }
        public void setEmiInternalCredential(String v) { this.emiInternalCredential = v; }
        /** Held ONLY by the deploy (never by the gateway/BFF): opens POST /internal/release/plugins,
         *  the "plugins ship as code" registration (docs/plugin-registration.md). Blank = closed. */
        private String releaseCredential = "";
        public String getReleaseCredential() { return releaseCredential; }
        public void setReleaseCredential(String v) { this.releaseCredential = v; }
        private String defaultPersonPassword = "fsp-person-2026";
        /** Red-team P1 fix: machine-plane allowlist for the recon paging op — a forged
         *  unregistered client id must never fire platform pages. */
        private String reconPageAllowedClients = "recon-worker";

        public String getReconPageAllowedClients() { return reconPageAllowedClients; }
        public void setReconPageAllowedClients(String v) { this.reconPageAllowedClients = v; }

        public String getDefaultPersonPassword() { return defaultPersonPassword; }
        public void setDefaultPersonPassword(String v) { this.defaultPersonPassword = v; }
    }

    public static class TigerBeetle {
        private int clusterId = 0;
        private String addresses = "127.0.0.1:3000";

        public int getClusterId() { return clusterId; }
        public void setClusterId(int v) { this.clusterId = v; }
        public String getAddresses() { return addresses; }
        public void setAddresses(String v) { this.addresses = v; }
    }

    /** Test hooks for the uncertain-commit recovery harness (never true in prod). */
    public static class Journal {
        private boolean forceUnknown = false;

        public boolean isForceUnknown() { return forceUnknown; }
        public void setForceUnknown(boolean v) { this.forceUnknown = v; }
    }

    /** F-12 push notifications (docs/architecture/12-notifications-and-push.md). Channel
     *  adapters are credential-gated: a channel with no credentials is simply ABSENT — no
     *  notification data ever leaves without the operator having provisioned keys
     *  (VAPID for Web Push, provider credentials for APNs/FCM). Keys are injected via env;
     *  never shipped, never logged. */
    public static class Push {
        private long dispatchFixedDelay = 2000;
        private boolean webpushEnabled = false;
        private String webpushSubject = "mailto:dev@fsp.local";
        private String webpushPublicKey = "";
        private String webpushPrivateKey = "";
        private boolean apnsEnabled = false;
        private String apnsHost = "api.push.apple.com";
        private String apnsTeamId = "";
        private String apnsKeyId = "";
        private String apnsKeyPem = "";
        private String apnsTopic = "";
        private boolean fcmEnabled = false;
        private String fcmProjectId = "";
        private String fcmCredentialsJson = "";

        public long getDispatchFixedDelay() { return dispatchFixedDelay; }
        public void setDispatchFixedDelay(long v) { this.dispatchFixedDelay = v; }
        public boolean isWebpushEnabled() { return webpushEnabled; }
        public void setWebpushEnabled(boolean v) { this.webpushEnabled = v; }
        public String getWebpushSubject() { return webpushSubject; }
        public void setWebpushSubject(String v) { this.webpushSubject = v; }
        public String getWebpushPublicKey() { return webpushPublicKey; }
        public void setWebpushPublicKey(String v) { this.webpushPublicKey = v; }
        public String getWebpushPrivateKey() { return webpushPrivateKey; }
        public void setWebpushPrivateKey(String v) { this.webpushPrivateKey = v; }
        public boolean isApnsEnabled() { return apnsEnabled; }
        public void setApnsEnabled(boolean v) { this.apnsEnabled = v; }
        public String getApnsHost() { return apnsHost; }
        public void setApnsHost(String v) { this.apnsHost = v; }
        public String getApnsTeamId() { return apnsTeamId; }
        public void setApnsTeamId(String v) { this.apnsTeamId = v; }
        public String getApnsKeyId() { return apnsKeyId; }
        public void setApnsKeyId(String v) { this.apnsKeyId = v; }
        public String getApnsKeyPem() { return apnsKeyPem; }
        public void setApnsKeyPem(String v) { this.apnsKeyPem = v; }
        public String getApnsTopic() { return apnsTopic; }
        public void setApnsTopic(String v) { this.apnsTopic = v; }
        public boolean isFcmEnabled() { return fcmEnabled; }
        public void setFcmEnabled(boolean v) { this.fcmEnabled = v; }
        public String getFcmProjectId() { return fcmProjectId; }
        public void setFcmProjectId(String v) { this.fcmProjectId = v; }
        public String getFcmCredentialsJson() { return fcmCredentialsJson; }
        public void setFcmCredentialsJson(String v) { this.fcmCredentialsJson = v; }
    }

    /** F-12 email channel (V29; docs/architecture/12-notifications-and-push.md §12.3).
     *  Credential-gated like push: enabled + SMTP coordinates via env (FSP_EMAIL_*),
     *  never shipped, never logged. The payload is the generic pointer + portal link. */
    public static class Email {
        private boolean enabled = false;
        private String host = "";
        private int port = 587;
        private String username = "";
        private String password = "";
        private String from = "fsp-notifications@fsp.local";
        private boolean startTls = true;
        /** Portal origin used to expand the notification's in-app path into a mailto link. */
        private String portalBaseUrl = "http://localhost:5173";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public int getPort() { return port; }
        public void setPort(int v) { this.port = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { this.username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
        public String getFrom() { return from; }
        public void setFrom(String v) { this.from = v; }
        public boolean isStartTls() { return startTls; }
        public void setStartTls(boolean v) { this.startTls = v; }
        public String getPortalBaseUrl() { return portalBaseUrl; }
        public void setPortalBaseUrl(String v) { this.portalBaseUrl = v; }
    }

    /** F-12 SMS channel (V29; docs/architecture/12-notifications-and-push.md §12.4).
     *  Phase-0 provider shape: generic JSON REST gateway (POST {from,to,text}, Bearer key)
     *  via env (FSP_SMS_*). Strictest payload: sender-branded ≤ 160 chars, no links. */
    public static class Sms {
        private boolean enabled = false;
        private String provider = "GENERIC";
        private String apiUrl = "";
        private String apiKey = "";
        private String senderId = "FSP";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String v) { this.apiUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getSenderId() { return senderId; }
        public void setSenderId(String v) { this.senderId = v; }
    }
}
