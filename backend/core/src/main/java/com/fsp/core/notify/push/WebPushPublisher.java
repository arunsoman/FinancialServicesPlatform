package com.fsp.core.notify.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Web Push (RFC 8030/8291/8292). Sends an RFC 8291-ENCRYPTED payload to the subscription's
 * push service with a VAPID JWT — the push service only ever sees ciphertext; end-to-end
 * encryption is why browser push is FSP's default channel. Credential-gated:
 * fsp.push.webpush.enabled + base64url VAPID keypair (a plain P-256 key, generatable with
 * openssl — see docs chapter 12).
 */
@Component
@ConditionalOnProperty(prefix = "fsp.push.webpush", name = "enabled", havingValue = "true")
public class WebPushPublisher implements PushPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PrivateKey asKey;
    private final String publicKeyB64u;
    private final String subject;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WebPushPublisher(com.fsp.core.config.FspProperties props) {
        var cfg = props.getPush();
        try {
            byte[] pk = Base64.getUrlDecoder().decode(cfg.getWebpushPrivateKey());
            Base64.getUrlDecoder().decode(cfg.getWebpushPublicKey()); // shape check
            this.asKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pk));
            this.publicKeyB64u = cfg.getWebpushPublicKey();
            this.subject = cfg.getWebpushSubject();
        } catch (RuntimeException e) {
            throw new IllegalStateException("fsp.push.webpush keys are enabled but invalid", e);
        } catch (Exception e) {
            throw new IllegalStateException("fsp.push.webpush key material failed to load", e);
        }
    }

    @Override
    public String platform() {
        return "webpush";
    }

    @Override
    public PushWire.PublishOutcome publish(PushWire.PushTarget target, PushWire.PushPayload payload) {
        try {
            byte[] plaintext = utf8(MAPPER.writeValueAsString(Map.of(
                "type", payload.type(),
                "title", payload.title(),
                "body", payload.body(),
                "tenantId", payload.tenantId(),
                "link", payload.link())));
            byte[] body = WebPushCrypto.encrypt(plaintext,
                Base64.getUrlDecoder().decode(target.key()),
                asKey.getEncoded(),
                Base64.getUrlDecoder().decode(target.auth()));
            String jwt = WebPushCrypto.vapidJwt(asKey, subject, origin(target.endpoint()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(target.endpoint()))
                .header("Authorization", "vapid t=" + jwt + ", k=" + publicKeyB64u)
                .header("TTL", "300")
                // RFC 8292 prefers Authorization; Crypto-Key stays for legacy endpoint compatibility
                .header("Crypto-Key", "p256dh=" + publicKeyB64u)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (resp.statusCode()) {
                case 200, 201, 202 -> new PushWire.PublishOutcome.Delivered();
                case 404, 410 -> new PushWire.PublishOutcome.Unreachable(true,
                    "subscription gone (" + resp.statusCode() + ")");
                case 401, 403 -> new PushWire.PublishOutcome.Unreachable(false,
                    "vapid/auth rejected (" + resp.statusCode() + ")");
                default -> new PushWire.PublishOutcome.Unreachable(false,
                    "push service answered " + resp.statusCode());
            };
        } catch (Exception e) {
            return new PushWire.PublishOutcome.Unreachable(false, "transport failure: " + e.getMessage());
        }
    }

    /** Origin extraction for the VAPID aud claim. */
    static String origin(String endpoint) {
        try {
            var uri = new URI(endpoint);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (Exception e) {
            return endpoint;
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
