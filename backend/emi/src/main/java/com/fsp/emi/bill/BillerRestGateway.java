package com.fsp.emi.bill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.emi.config.EmiProperties;
import com.fsp.shared.rails.RailCapability;
import com.fsp.shared.rails.RailGuard;
import com.fsp.shared.rails.RailResult;
import com.fsp.shared.rails.RailTransportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * BILLER_REST protocol adapter (plan §3.4): JDK HttpClient under the full Stage-1
 * RailGuard admission ladder (429 freeze → breaker → bulkhead).
 *
 * <p>Wire mapping — POST {endpoint}/bills, probe GET {endpoint}/bills/{railRef}:</p>
 * <ul>
 *   <li>2xx {@code {"status":"accepted","rail_ref":...}} → Accepted</li>
 *   <li>2xx {@code {"status":"pending",...}} → AcceptedPending (await recon recognition)</li>
 *   <li>2xx/rejects 4xx → Rejected(permanent) — business rejection, safe to void the hold</li>
 *   <li>429 → Failed(RateLimited, Retry-After) — guard window reseeded, nothing submitted</li>
 *   <li>5xx → Failed(TemporarilyUnavailable) — biller saw the request; outcome unknown</li>
 *   <li>timeout / transport error → RailTransportException(CommUnreachable) — outcome
 *       unknown, the saga engine must never treat this as a submit failure</li>
 * </ul>
 *
 * <p>PII rules: account refs and bodies are never logged; only saga id + outcome tokens.</p>
 */
@Slf4j
@Service
public class BillerRestGateway implements BillerGateway {

    private final ObjectMapper mapper;
    private final EmiProperties props;
    private final HttpClient http;
    private final RailGuard guard;

    public BillerRestGateway(EmiProperties props) {
        this.mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        this.props = props;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(props.getBiller().getTimeoutMs()))
            .build();
        this.guard = new RailGuard();
    }

    @Override
    public RailResult submit(Biller biller, BillRequest request) {
        URI url = billsUrl(biller);
        return guard.call(connectorId(biller), hostKey(biller), submitCapability(), () -> {
            long start = System.nanoTime();
            try {
                HttpResponse<String> resp = http.send(request(url, biller, "POST", submitBody(request), true),
                    HttpResponse.BodyHandlers.ofString());
                return mapSubmitResponse(biller.code, resp);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RailTransportException(
                    new RailResult.FailureKind.CommUnreachable(elapsed(start)), e);
            } catch (IOException e) {
                // Outcome UNKNOWN: the request may have reached the biller. Never a submit failure.
                throw new RailTransportException(
                    new RailResult.FailureKind.CommUnreachable(elapsed(start)), e);
            }
        });
    }

    @Override
    public Optional<RailResult> probe(Biller biller, BillRequest request, String railRef) {
        URI url = billsUrl(biller).resolve("/bills/" + URLEncoder.encode(railRef, StandardCharsets.UTF_8));
        try {
            RailResult result = guard.call(connectorId(biller), hostKey(biller),
                new RailCapability.StatusProbe(Duration.ofSeconds(30)), () -> {
                    long start = System.nanoTime();
                    try {
                        HttpResponse<String> resp = http.send(request(url, biller, "GET", null, false),
                            HttpResponse.BodyHandlers.ofString());
                        return mapProbeResponse(resp);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RailTransportException(
                            new RailResult.FailureKind.CommUnreachable(elapsed(start)), e);
                    } catch (IOException e) {
                        throw new RailTransportException(
                            new RailResult.FailureKind.CommUnreachable(elapsed(start)), e);
                    }
                });
            if (result instanceof RailResult.Accepted || result instanceof RailResult.Rejected) {
                return Optional.of(result);
            }
            return Optional.empty();
        } catch (RailTransportException e) {
            // Probes are safe to re-run: transport failure = inconclusive, not "not found".
            log.warn("biller_probe_inconclusive biller={} saga={}", biller.code, request.sagaId());
            return Optional.empty();
        }
    }

    // --------------------------------------------------------------- wire helpers

    private HttpRequest request(URI url, Biller biller, String method, String jsonBody, boolean authenticated) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
            .timeout(Duration.ofMillis(props.getBiller().getTimeoutMs()))
            .header("Accept", "application/json");
        if (authenticated) {
            // Credential is resolved from the env var NAMED on the biller row — never stored.
            String credential = System.getenv(biller.endpointCredentialEnvRef);
            if (credential != null && !credential.isBlank()) {
                b.header("Authorization", "Bearer " + credential);
            }
        }
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        return b.build();
    }

    private String submitBody(BillRequest request) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("saga_id", request.sagaId().toString());
            body.put("account_ref", request.accountRef());
            body.put("currency", request.currency());
            body.put("amount_minor", request.amountOnWire()); // string on the wire (§2b)
            body.put("idempotency_key", request.idempotencyKey());
            return mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("bill request serialization", e);
        }
    }

    private RailResult mapSubmitResponse(String billerCode, HttpResponse<String> resp) {
        int sc = resp.statusCode();
        if (sc >= 200 && sc < 300) {
            JsonNode n = parse(resp);
            String status = text(n, "status");
            String railRef = text(n, "rail_ref");
            return switch (status == null ? "" : status) {
                case "accepted" -> new RailResult.Accepted(railRef, null);
                case "pending", "awaiting_recognition" -> new RailResult.AcceptedPending(railRef);
                case "rejected" -> new RailResult.Rejected(
                    text(n, "code") == null ? "BILLER_REJECTED" : text(n, "code"), true);
                default -> new RailResult.Failed(
                    new RailResult.FailureKind.DefinitiveFailure("biller-status:" + status));
            };
        }
        if (sc == 429) {
            return new RailResult.Failed(
                new RailResult.FailureKind.RateLimited(retryAfter(resp)));
        }
        if (sc >= 400 && sc < 500) {
            // Business rejection (biller parsed and refused the payment): hold is voidable.
            JsonNode n = parse(resp);
            String code = text(n, "code") == null ? "HTTP_" + sc : text(n, "code");
            return new RailResult.Rejected(code, true);
        }
        return new RailResult.Failed(new RailResult.FailureKind.TemporarilyUnavailable(null));
    }

    private RailResult mapProbeResponse(HttpResponse<String> resp) {
        int sc = resp.statusCode();
        if (sc == 404) {
            return new RailResult.Rejected("NOT_FOUND", true);
        }
        if (sc >= 200 && sc < 300) {
            JsonNode n = parse(resp);
            return switch (text(n, "status") == null ? "" : text(n, "status")) {
                case "settled" -> new RailResult.Accepted(text(n, "rail_ref"), null);
                case "rejected", "unknown" -> new RailResult.Rejected(
                    text(n, "code") == null ? "BILLER_REJECTED" : text(n, "code"), true);
                default -> new RailResult.Failed(new RailResult.FailureKind.TemporarilyUnavailable(null));
            };
        }
        return new RailResult.Failed(new RailResult.FailureKind.TemporarilyUnavailable(null));
    }

    private JsonNode parse(HttpResponse<String> resp) {
        try {
            return mapper.readTree(resp.body() == null || resp.body().isBlank() ? "{}" : resp.body());
        } catch (IOException e) {
            log.warn("biller_response_unparseable status={}", resp.statusCode());
            return mapper.createObjectNode();
        }
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static Duration retryAfter(HttpResponse<String> resp) {
        String ra = resp.headers().firstValue("Retry-After").orElse(null);
        if (ra != null) {
            try {
                return Duration.ofSeconds(Long.parseLong(ra.trim()));
            } catch (NumberFormatException ignored) {
                /* fall through */
            }
        }
        return Duration.ofSeconds(30);
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private URI billsUrl(Biller biller) {
        if (biller.endpointUrl == null || biller.endpointUrl.isBlank()) {
            throw new IllegalStateException("biller " + biller.code + " has no endpoint_url configured");
        }
        URI base = URI.create(biller.endpointUrl.endsWith("/")
            ? biller.endpointUrl.substring(0, biller.endpointUrl.length() - 1)
            : biller.endpointUrl);
        return base.resolve("/bills");
    }

    private RailCapability.Parallel submitCapability() {
        var b = props.getBiller();
        return new RailCapability.Parallel(b.getParallelism(),
            Duration.ofMillis(b.getTimeoutMs()), Duration.ofMillis(b.getTimeoutMs()));
    }

    private static String connectorId(Biller biller) {
        return "biller:" + biller.code;
    }

    private static String hostKey(Biller biller) {
        URI base = URI.create(biller.endpointUrl == null ? "unknown" : biller.endpointUrl);
        return base.getHost() == null ? "unknown" : base.getHost();
    }
}
