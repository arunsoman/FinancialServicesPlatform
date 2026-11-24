package com.fsp.bff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fsp.bff.BffDtos.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * BFF seam for act-as ("sudo into a tenant", plans/act-as-tenant-plan.md §3.3). The browser
 * NEVER holds the {@code X-Act-As} header: the live session id is stored server-side in the
 * BFF session (like the IdP token), and {@link ApiProxyController} injects it on every proxied
 * /api call while it is present. Start/exit go to core {@code /v1/act-as} with the session's
 * IdP token (+ DPoP proof, same as the proxy); core records ACT_AS_START/END for the real
 * person. Core's views and problem+json pass through verbatim, so the SPA sees the same
 * stable codes core defines (ACT_AS_OFF, ACT_AS_NO_HOME, TENANT_NOT_FOUND, ACT_AS_EXPIRED…).
 */
@Slf4j
@RestController
public class BffActAsController {

    static final String SESSION_ACT_AS = "FSP_SESSION_ACT_AS";

    public record StartRequest(UUID tenantId) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient rest;
    private final BffProperties props;
    private final IdpClient idp;

    private final SessionTokens tokens;

    public BffActAsController(RestClient rest, BffProperties props, IdpClient idp, SessionTokens tokens) {
        this.tokens = tokens;
        this.rest = rest;
        this.props = props;
        this.idp = idp;
    }

    /** GET /bff/act-as → { active: boolean, session: ActAsView | null } (core view passthrough). */
    @GetMapping("/bff/act-as")
    public ResponseEntity<String> current(HttpServletRequest request) {
        if (BffSessionController.current(request) == null) {
            return authRequired();
        }
        Upstream up = callCore(request, HttpMethod.GET, "/v1/act-as", null);
        if (!up.ok()) {
            return forwardProblem(up.status(), up.body());
        }
        JsonNode view = parseView(up.body());
        return ResponseEntity.ok(wrap(view, view != null));
    }

    /** POST /bff/act-as {tenantId} → { active: true, session: ActAsView }; the id is cached server-side. */
    @PostMapping("/bff/act-as")
    public ResponseEntity<String> start(HttpServletRequest request, @RequestBody StartRequest body) {
        SessionUser user = BffSessionController.current(request);
        if (user == null) {
            return authRequired();
        }
        if (body == null || body.tenantId() == null) {
            return problem(400, "ACT_AS_INVALID", "tenantId is required");
        }
        Upstream up = callCore(request, HttpMethod.POST, "/v1/act-as",
            "{\"tenantId\":\"" + body.tenantId() + "\"}");
        if (!up.ok()) {
            return forwardProblem(up.status(), up.body());
        }
        JsonNode view = parseView(up.body());
        if (view == null || !StringUtils.hasText(view.path("sessionId").asText())) {
            return problem(502, "ACT_AS_UPSTREAM_BAD", "core returned no act-as session");
        }
        // One live act-as session per BFF session; core already ends the person's previous
        // row on a new start — the BFF cache must never lag behind that.
        sessionOf(request).setAttribute(SESSION_ACT_AS, view.path("sessionId").asText());
        log.info("bff_act_as_started username={} targetTenantId={} sessionId={}",
            user.username(), view.path("targetTenantId").asText(), view.path("sessionId").asText());
        return ResponseEntity.ok(wrap(view, true));
    }

    /** DELETE /bff/act-as → exit; the BFF cache is cleared even if core's row already expired. */
    @DeleteMapping("/bff/act-as")
    public ResponseEntity<String> exit(HttpServletRequest request) {
        if (BffSessionController.current(request) == null) {
            return authRequired();
        }
        Upstream up = callCore(request, HttpMethod.DELETE, "/v1/act-as", null);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_ACT_AS);
        }
        // Core may answer 401 ACT_AS_EXPIRED for an already-idled row: exiting is harmless noise,
        // do not surface it — the user's intent (leave act-as) is fulfilled by clearing the cache.
        if (!up.ok() && up.status() != 401) {
            return forwardProblem(up.status(), up.body());
        }
        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ plumbing */

    private record Upstream(int status, String body) {
        boolean ok() { return status >= 200 && status < 300; }
    }

    /** Person-plane call to core: bearer + DPoP, NEVER the session's X-Act-As (ActAsFilter
     *  excludes /v1/act-as anyway — belt and braces). */
    private Upstream callCore(HttpServletRequest request, HttpMethod method, String path, String body) {
        String token = tokens.liveToken(request);
        String dpopKey = BffSessionController.dpopKey(request);
        String url = props.getGateway().getBaseUrl() + path;
        try {
            var spec = rest.method(method).uri(url)
                .header("Authorization", "Bearer " + token)
                .header("X-FSP-Client-Id", "fsp-bff")
                .header("Accept", "application/json");
            if (method != HttpMethod.GET) {
                // AGENTS §2e — every mutating core request carries an Idempotency-Key; the BFF
                // generates it because the browser never sees the internal hop.
                spec.header("Idempotency-Key", UUID.randomUUID().toString());
            }
            if (body != null) {
                spec.header("Content-Type", "application/json");
            }
            if (dpopKey != null) {
                try {
                    spec.header("DPoP", idp.createDPoPProofForApi(dpopKey, url, method.name()));
                } catch (Exception e) {
                    log.warn("bff_act_as_dpop_proof_failed path={} detail={}", path, e.getMessage());
                }
            }
            ResponseEntity<String> res = (body == null ? spec : spec.body(body))
                .exchange((req, res2) -> new ResponseEntity<>(new String(res2.getBody().readAllBytes(), StandardCharsets.UTF_8), res2.getStatusCode()));
            return new Upstream(res.getStatusCode().value(), res.getBody());
        } catch (RestClientException e) {
            log.warn("bff_act_as_upstream_failed path={} detail={}", path, e.getMessage());
            return new Upstream(502, null);
        }
    }

    /** Core's ActAsView JSON or the literal null → JsonNode view or null. */
    private static JsonNode parseView(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || node.isNull() || !node.hasNonNull("sessionId")) return null;
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private static String wrap(JsonNode view, boolean active) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("active", active);
        out.set("session", view == null ? MAPPER.nullNode() : view);
        return out.toString();
    }

    private static HttpSession sessionOf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) throw new IllegalStateException("session vanished between check and use");
        return session;
    }

    private static ResponseEntity<String> authRequired() {
        return ResponseEntity.status(401)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body("{\"code\":\"NO_SESSION\",\"title\":\"Unauthorized\",\"detail\":\"Authentication required\"}");
    }

    private static ResponseEntity<String> problem(int status, String code, String detail) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body("{\"code\":\"%s\",\"title\":\"%s\",\"detail\":\"%s\"}".formatted(code, code, detail));
    }

    private static ResponseEntity<String> forwardProblem(int status, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"title\":\"Upstream error\"}");
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(rawBody);
    }
}
