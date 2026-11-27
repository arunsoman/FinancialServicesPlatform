package com.fsp.core.notify.push;

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
import java.util.Base64;
import java.util.Map;

/**
 * Apple Push Notification service (HTTP/2 provider API, token-based auth). Credential-gated:
 * enabled = fsp.push.apns.enabled + team-id + key-id + topic + the .p8 signing key (PEM, via
 * env — mounted like the IdP key, never baked in). A 403/410 provider answer retires the
 * device token (definitive); 5xx leaves it healthy.
 */
@Component
@ConditionalOnProperty(prefix = "fsp.push.apns", name = "enabled", havingValue = "true")
public class ApnsPublisher implements PushPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String host;
    private final String teamId;
    private final String keyId;
    private final String topic;
    private final PrivateKey key;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10)).build();

    public ApnsPublisher(com.fsp.core.config.FspProperties props) throws Exception {
        var cfg = props.getPush();
        this.host = cfg.getApnsHost();
        this.teamId = cfg.getApnsTeamId();
        this.keyId = cfg.getApnsKeyId();
        this.topic = cfg.getApnsTopic();
        this.key = pemEcPrivateKey(cfg.getApnsKeyPem());
    }

    @Override
    public String platform() {
        return "apns";
    }

    @Override
    public PushWire.PublishOutcome publish(PushWire.PushTarget target, PushWire.PushPayload payload) {
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                "aps", Map.of("alert", Map.of("title", payload.title(), "body", payload.body()),
                    "sound", "default"),
                "fsp", Map.of("type", payload.type(), "tenantId", payload.tenantId(), "link", payload.link())));
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://" + host + "/3/device/" + target.token()))
                .header("authorization", "bearer " + providerJwt(target.token()))
                .header("apns-topic", topic)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (resp.statusCode()) {
                case 200 -> new PushWire.PublishOutcome.Delivered();
                case 403, 404, 410 -> new PushWire.PublishOutcome.Unreachable(true,
                    "device token retired (" + resp.statusCode() + ")");
                case 400, 429 -> new PushWire.PublishOutcome.Unreachable(false,
                    "apns refused (" + resp.statusCode() + ") " + resp.body());
                default -> new PushWire.PublishOutcome.Unreachable(false,
                    "apns answered " + resp.statusCode());
            };
        } catch (Exception e) {
            return new PushWire.PublishOutcome.Unreachable(false, "transport failure: " + e.getMessage());
        }
    }

    /** APNs provider token — ES256 JWT (header kid=APNs key id), 60-min max life. */
    private String providerJwt(String token) {
        try {
            long exp = java.time.Instant.now().plus(Duration.ofMinutes(55)).getEpochSecond();
            String header = b64u("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\",\"typ\":\"JWT\"}");
            String claims = b64u("{\"iss\":\"" + teamId + "\",\"iat\":" + (exp - 3300) + ",\"exp\":" + exp + "}");
            String input = header + "." + claims;
            Signature sig = Signature.getInstance("SHA256withECDSAINP1363FORMAT");
            sig.initSign(key);
            sig.update(input.getBytes(StandardCharsets.UTF_8));
            String jws = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
            return input + "." + jws;
        } catch (Exception e) {
            throw new IllegalStateException("apns provider jwt failed", e);
        }
    }

    /** Loads an EC private key from raw PKCS8 base64 or PEM (with optional "-----BEGIN" wrapper). */
    static PrivateKey pemEcPrivateKey(String pemOrBase64) throws Exception {
        String s = pemOrBase64.trim();
        if (s.startsWith("-----")) {
            s = s.replaceAll("-----BEGIN [^-]+-----", "").replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        } else if (Files.exists(Path.of(s))) {
            // a path value: read the file (mounted secret), never log its contents
            s = Files.readString(Path.of(s)).replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "").replaceAll("\\s", "");
        }
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(s)));
    }

    private static String b64u(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
