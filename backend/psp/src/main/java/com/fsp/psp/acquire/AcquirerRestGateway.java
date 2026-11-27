package com.fsp.psp.acquire;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.psp.connector.BankConnector;
import com.fsp.shared.rails.RailCapability;
import com.fsp.shared.rails.RailGuard;
import com.fsp.shared.rails.RailResult;
import com.fsp.shared.rails.RailTransportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST_OPEN_BANKING protocol adapter (plan §3.4): JDK HttpClient under the full Stage-1
 * RailGuard admission ladder (429 freeze → breaker → bulkhead).
 *
 * <p>Wire mapping — POST {endpoint}/payments:</p>
 * <ul>
 *   <li>2xx {@code {"status":"authorized","rail_ref":...}} → Accepted</li>
 *   <li>2xx {@code {"status":"pending",...}} → AcceptedPending (await capture/confirm)</li>
 *   <li>4xx → Rejected(permanent) — scheme decision, a business event</li>
 *   <li>429 → Failed(RateLimited, Retry-After) — guard window reseeded, nothing sent</li>
 *   <li>5xx → Failed(TemporarilyUnavailable) — bank saw the request; outcome unknown</li>
 *   <li>timeout / transport error → RailTransportException(CommUnreachable) — outcome
 *       unknown; the saga engine must never treat this as a rejection</li>
 * </ul>
 *
 * <p>PII rules: no card/pan/account data is ever logged; only pspRef + outcome tokens.</p>
 */
@Slf4j
@Component
public class AcquirerRestGateway implements AcquirerGateway {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final RailGuard guard;

    public AcquirerRestGateway() {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.guard = new RailGuard();
    }

    @Override
    public RailResult authorize(BankConnector connector, AcquireRequest request) {
        URI url = paymentsUri(connector);
        return guard.call(connector.bankCode, connector.bankCode,
            new RailCapability.Parallel(4, Duration.ofMillis(connector.connectTimeoutMs),
                Duration.ofMillis(connector.readTimeoutMs)), () -> {
                long start = System.nanoTime();
                try {
                    HttpResponse<String> resp = http.send(request(connector, url, request),
                        HttpResponse.BodyHandlers.ofString());
                    return mapResponse(resp);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RailTransportException(
                        new RailResult.FailureKind.CommUnreachable(elapsed(start)), e);
                } catch (IOException e) {
                    // Outcome UNKNOWN: the request may have reached the bank.
                    throw new RailTransportException(
                        new RailResult.FailureKind.CommUnreachable(elapsed(start)), e);
                }
            });
    }

    // --------------------------------------------------------------- wire helpers

    private static URI paymentsUri(BankConnector connector) {
        var endpoint = java.util.Optional.ofNullable(System.getenv(connector.endpointCredentialEnvRef))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .orElseGet(() -> System.getenv("FSP_PSP_DEMO_ENDPOINT"));
        // No endpoint resolved → treat as a definitive transport misconfiguration.
        if (endpoint == null || endpoint.isBlank()) {
            throw new RailTransportException(
                new RailResult.FailureKind.DefinitiveFailure("PSP_ENDPOINT_UNRESOLVED"), null);
        }
        return URI.create(endpoint.strip() + "/payments");
    }

    private HttpRequest request(BankConnector connector, URI url, AcquireRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("psp_ref", r.pspRef());
        body.put("merchant_code", r.merchantCode());
        body.put("amount_minor", r.amountMinor());
        body.put("currency", r.currency());
        String credential = credentialOf(connector);
        return HttpRequest.newBuilder(url)
            .timeout(Duration.ofMillis(connector.readTimeoutMs))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-Idempotency-Key", r.pspRef()) // rail-level exactly-once anchor
            .header("Authorization", "Bearer " + credential)
            .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
            .build();
    }

    /**
     * Credential resolved from the env var NAMED on the connector row — never stored,
     * never logged. Missing env var fails the call locally (definitive, not unknown).
     */
    private static String credentialOf(BankConnector connector) {
        String v = System.getenv(connector.endpointCredentialEnvRef);
        if (v == null || v.isBlank()) {
            throw new RailTransportException(
                new RailResult.FailureKind.DefinitiveFailure("PSP_CREDENTIAL_UNRESOLVED"), null);
        }
        return v.strip();
    }

    private RailResult mapResponse(HttpResponse<String> resp) {
        int sc = resp.statusCode();
        if (sc >= 500) {
            return new RailResult.Failed(new RailResult.FailureKind.TemporarilyUnavailable(null));
        }
        if (sc == 429) {
            String retryAfter = resp.headers().firstValue("Retry-After").orElse(null);
            Duration window = retryAfterSeconds(retryAfter);
            return new RailResult.Failed(new RailResult.FailureKind.RateLimited(window));
        }
        String body = resp.body() == null ? "" : resp.body();
        if (sc >= 200 && sc < 300) {
            try {
                var root = mapper.readTree(body);
                String status = root.path("status").asText("");
                String railRef = root.path("rail_ref").asText(null);
                if ("authorized".equalsIgnoreCase(status)) {
                    return new RailResult.Accepted(railRef, null);
                }
                if ("pending".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(
                        root.path("state").asText(""))) {
                    return new RailResult.AcceptedPending(railRef);
                }
                return new RailResult.Rejected("PSP_SCHEME_UNPARSEABLE", true);
            } catch (Exception e) {
                log.warn("psp_rail_response_unparseable status={}", sc);
                return new RailResult.Rejected("PSP_SCHEME_UNPARSEABLE", true);
            }
        }
        // Any 4xx is a scheme/business decision (permanent until the merchant retries anew).
        return new RailResult.Rejected("PSP_SCHEME_REJECTED_" + sc, true);
    }

    private static Duration retryAfterSeconds(String v) {
        try {
            return v == null ? Duration.ofSeconds(60) : Duration.ofSeconds(Long.parseLong(v.strip()));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(60);
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("psp_rail_request_serialize_failed", e);
        }
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
