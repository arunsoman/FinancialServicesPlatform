package com.fsp.core.notify.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.config.FspProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * SMS gateway transport adapter (V29; docs/architecture/12-notifications-and-push.md
 * §12.4). Credential-gated: {@code fsp.sms.enabled} + provider coordinates via env
 * (FSP_SMS_*). Phase-0 ships ONE provider shape — a generic JSON REST gateway
 * (POST {from, to, text}, Bearer key) — the adapter seam keeps other provider protocols
 * a pure bean addition, never a schema change.
 *
 * SMS payload budget is the STRICTEST of all channels ( plaintext end-to-end at the
 * telco): sender-branded ≤ 160 chars, generic copy, NEVER a link, identifier, amount or
 * code — FSP is not an auth-factor channel here (§12.5).
 *
 * Outcomes: 2xx → Delivered; 4xx (except 429 rate limiting) → definitive Unreachable;
 * 429 / 5xx / transport errors → transient (bounded retry). No PII in logs (§2c).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fsp.sms", name = "enabled", havingValue = "true")
public class SmsPublisher implements OutboundChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SMS_MAX = 160;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiUrl;
    private final String apiKey;
    private final String senderId;
    private final String providerId;

    public SmsPublisher(FspProperties props) {
        var cfg = props.getSms();
        if (cfg.getApiUrl().isBlank()) {
            throw new IllegalStateException("fsp.sms is enabled but fsp.sms.api-url is empty");
        }
        this.apiUrl = cfg.getApiUrl();
        this.apiKey = cfg.getApiKey();
        this.senderId = cfg.getSenderId();
        this.providerId = cfg.getProvider().isBlank() ? "SMS" : cfg.getProvider().toUpperCase();
    }

    @Override
    public String channel() {
        return ChannelWire.SMS;
    }

    @Override
    public String deliveryTag() {
        return providerId;
    }

    @Override
    public ChannelWire.SendOutcome send(ChannelWire.Target target, ChannelWire.Pointer pointer) {
        String msisdn = ChannelAddresses.normaliseE164(target.contact());
        if (msisdn == null) {
            return new ChannelWire.SendOutcome.Skipped("no_valid_msisdn");
        }
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                "from", senderId,
                "to", msisdn,
                "text", shortText(pointer)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                log.info("sms_dispatched person={} provider={} result=accepted", target.personId(), providerId);
                return new ChannelWire.SendOutcome.Delivered();
            }
            boolean transientError = res.statusCode() == 429 || res.statusCode() >= 500;
            log.warn("sms_unreachable person={} provider={} status={} transient={}",
                target.personId(), providerId, res.statusCode(), transientError);
            return new ChannelWire.SendOutcome.Unreachable(!transientError, "gateway_status_" + res.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ChannelWire.SendOutcome.Unreachable(false, "interrupted");
        } catch (Exception e) {
            // transport-level failure = the gateway is down, the number may be fine → transient
            log.warn("sms_unreachable person={} provider={} reason={}",
                target.personId(), providerId, e.getClass().getSimpleName());
            return new ChannelWire.SendOutcome.Unreachable(false, "gateway_unreachable");
        }
    }

    /** Sender-branded, ≤ 160 chars, generic copy only — no link, no structure (§12.5). */
    private String shortText(ChannelWire.Pointer pointer) {
        String base = senderId + ": " + pointer.title() + ". " + pointer.body() + ".";
        if (base.length() > SMS_MAX) {
            base = base.substring(0, SMS_MAX - 1) + "…";
        }
        return base;
    }
}
