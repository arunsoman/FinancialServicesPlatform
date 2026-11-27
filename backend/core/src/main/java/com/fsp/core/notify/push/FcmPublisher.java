package com.fsp.core.notify.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Firebase Cloud Messaging v1 (Android + cross-platform fallback plane). Credential-gated:
 * enabled = fsp.push.fcm.enabled + project-id + a Google service-account key (client_email +
 * private_key RSA PEM) via env. Provider JWT-bearer OAuth2 exchange, 1-hour access tokens
 * cached in-process. Response body `{error: UNREGISTERED}` retires the token (definitive).
 */
@Component
@ConditionalOnProperty(prefix = "fsp.push.fcm", name = "enabled", havingValue = "true")
public class FcmPublisher implements PushPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    /** Stable refresh-critical-section key so all senders single-flight on ONE token refresh. */
    private static final UUID TOKEN_SLOT = UUID.nameUUIDFromBytes("fsp:fcm-token-refresh".getBytes(StandardCharsets.UTF_8));

    private final com.fsp.core.common.KeyLocks keyLocks;
    private final String projectId;
    private final String clientEmail;
    private final PrivateKey key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public FcmPublisher(com.fsp.core.config.FspProperties props, com.fsp.core.common.KeyLocks keyLocks) throws Exception {
        this.keyLocks = keyLocks;
        var cfg = props.getPush();
        this.projectId = cfg.getFcmProjectId();
        String raw = cfg.getFcmCredentialsJson().trim();
        if (Files.exists(Path.of(raw))) raw = Files.readString(Path.of(raw));
        JsonNode sa = MAPPER.readTree(raw);
        this.clientEmail = sa.path("client_email").asText();
        String pkPem = sa.path("private_key").asText();
        if (clientEmail.isBlank() || pkPem.isBlank()) {
            throw new IllegalStateException("fsp.push.fcm credentials json lacks client_email/private_key");
        }
        this.key = KeyFactory.getInstance("RSA").generatePrivate(
            new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(
                pkPem.replaceAll("-----BEGIN [^-]+-----", "").replaceAll("-----END [^-]+-----", ""))));
    }

    @Override
    public String platform() {
        return "fcm";
    }

    @Override
    public PushWire.PublishOutcome publish(PushWire.PushTarget target, PushWire.PushPayload payload) {
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                "message", Map.of(
                    "token", target.token(),
                    "notification", Map.of("title", payload.title(), "body", payload.body()),
                    "data", Map.of("type", payload.type(), "tenantId", payload.tenantId(),
                        "link", payload.link()))));
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return new PushWire.PublishOutcome.Delivered();
            String error = MAPPER.readTree(resp.body()).path("error").path("details").path(0)
                .path("errorCode").asText("");
            boolean unregistered = resp.statusCode() == 404 || error.contains("UNREGISTERED") || error.contains("INVALID_ARGUMENT");
            return new PushWire.PublishOutcome.Unreachable(unregistered,
                "fcm answered " + resp.statusCode() + (error.isBlank() ? "" : " " + error));
        } catch (Exception e) {
            return new PushWire.PublishOutcome.Unreachable(false, "transport failure: " + e.getMessage());
        }
    }

    /**
     * Access token with single-flight refresh (AGENTS.md §2d.2). Cheap volatile fast-path first;
     * the refresh (network I/O) runs under a fair per-key KeyLocks stripe instead of a shared
     * process-wide object monitor — push senders never queue behind a cold token exchange, only
     * concurrent refreshers wait, and `withLock` cannot leak the lock on a failed exchange.
     */
    private String accessToken() {
        String token = cachedToken;
        if (token != null && Instant.now().isBefore(tokenExpiry)) return token;
        return keyLocks.withLock("fcm-token-refresh", TOKEN_SLOT, () -> {
            if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) return cachedToken;
            try {
                return refreshAccessToken();
            } catch (Exception e) {
                throw new IllegalStateException("fcm_token_refresh_failed", e);
            }
        });
    }

    /** OAuth2 JWT-bearer exchange against the service account (RS256); 1-hour token cached 45 min. */
    private String refreshAccessToken() throws Exception {
        long exp = Instant.now().plus(Duration.ofMinutes(60)).getEpochSecond();
        String input = b64u(new ObjectMapper().writeValueAsString(Map.of("alg", "RS256", "typ", "JWT")))
            + "." + b64u("{\"iss\":\"" + clientEmail + "\",\"scope\":\"https://www.googleapis.com/auth/firebase.messaging\","
            + "\"aud\":\"" + TOKEN_URL + "\",\"exp\":" + exp + ",\"iat\":" + (exp - 3600) + "}");
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(input.getBytes(StandardCharsets.UTF_8));
        String assertion = input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());

        String form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
            + "&assertion=" + assertion;
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("fcm token exchange failed: " + resp.statusCode());
        }
        JsonNode node = MAPPER.readTree(resp.body());
        cachedToken = node.path("access_token").asText();
        tokenExpiry = Instant.now().plus(Duration.ofMinutes(45));
        return cachedToken;
    }

    private static String b64u(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
