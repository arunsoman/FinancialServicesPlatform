package com.fsp.bff;

import com.fsp.bff.BffDtos.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * The trusted internal hop (FAPI2 guide §2): the ONLY bridge between the SPA session and
 * the api gateway. Inbound identity/forwarding headers are STRIPPED; the session's IdP token
 * is attached as Bearer; the gateway verifies it and re-issues X-FSP-* identity to core.
 * Clients can never spoof tokens or X-FSP-* headers through the BFF.
 * FAPI 2.0: Adds DPoP proof header to all proxied API calls.
 */
@Slf4j
@RestController
public class ApiProxyController {

    private static final Set<String> ALLOWED = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    /** Upstream 401 codes that mean THIS session's token is dead — the only ones allowed to invalidate it.
     * INVALID_DPOP_PROOF is deliberately NOT here: a proof failure is per-request (a fresh proof is
     * generated on the next call), not session death. */
    private static final Set<String> TOKEN_REJECTION_CODES = Set.of("TOKEN_MISSING", "TOKEN_INVALID",
        "AUTH_REQUIRED", "NO_SESSION", "ACT_AS_EXPIRED", "UNAUTHORIZED", "INVALID_TOKEN");
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "transfer-encoding",
        "te", "upgrade", "proxy-authorization", "proxy-authenticate", "set-cookie", "cookie");
    /** Clients must not smuggle internal headers — stripped before the trusted re-issue.
     *  x-act-as included: the live act-as session id is BFF-session state (BffActAsController),
     *  never a client-supplied header. */
    private static final Set<String> NEVER_PASSED = Set.of("x-fsp-user-id", "x-fsp-tenant-id", "x-fsp-username",
        "x-fsp-client-id", "x-fsp-internal-credential", "authorization", "host", "forwarded", "cookie",
        "x-act-as");

    private final RestClient rest;
    private final BffProperties props;
    private final IdpClient idp;

    private final SessionTokens tokens;

    public ApiProxyController(RestClient rest, BffProperties props, IdpClient idp, SessionTokens tokens) {
        this.tokens = tokens;
        this.rest = rest;
        this.props = props;
        this.idp = idp;
    }

    @RequestMapping("/api/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod().toUpperCase();
        if (!ALLOWED.contains(method)) {
            reject(response, 405, "METHOD_NOT_ALLOWED", "Method not proxied");
            return;
        }

        SessionUser user = BffSessionController.current(request);
        if (user == null) {
            log.warn("proxy_no_session path={} remote={}", request.getRequestURI(), request.getRemoteAddr());
            reject(response, 401, "NO_SESSION", "Authentication required (GET /bff/auth/login)");
            return;
        }
        // renewed if about to expire; null = the session just ended (absolute limit / renewal refused)
        String token = tokens.liveToken(request);
        if (!StringUtils.hasText(token)) {
            reject(response, 401, "SESSION_EXPIRED", "Your session has ended — sign in again");
            return;
        }
        String dpopKey = BffSessionController.dpopKey(request);
        // plans/act-as-tenant-plan.md §3.3: every proxied call rides the BFF session's act-as
        // id (server-side state); a stale/foreign id is refused closed by core (ACT_AS_EXPIRED).
        HttpSession session = request.getSession(false);
        String actAsSessionId = session == null ? null
            : (String) session.getAttribute(BffActAsController.SESSION_ACT_AS);

        // Generate DPoP proof for this API call (FAPI 2.0 requirement)
        String gatewayPath = request.getRequestURI().replaceFirst("^/api", "/v1");
        String[] dpopProofHolder = { null };
        if (dpopKey != null) {
            try {
                dpopProofHolder[0] = idp.createDPoPProofForApi(dpopKey, props.getGateway().getBaseUrl() + gatewayPath, method);
            } catch (Exception e) {
                log.warn("dpop_proof_generation_failed path={}", request.getRequestURI(), e);
            }
        }

        byte[] body = readBody(request);
        if (body == null) {
            reject(response, 413, "BODY_TOO_LARGE",
                "Request body exceeds %d bytes".formatted(props.getProxy().getMaxBodyBytes()));
            return;
        }

        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        URI target = URI.create(props.getGateway().getBaseUrl() + gatewayPath + query);

        try {
            var spec = rest.method(HttpMethod.valueOf(method))
                .uri(target)
                .headers(headers -> copyTrusted(request, method, token, dpopProofHolder[0], actAsSessionId, headers));
            ResponseEntity<byte[]> upstream = (body.length == 0 ? spec : spec.body(body))
                .exchange((req, res) -> {
                    byte[] raw = res.getBody().readAllBytes();
                    ResponseEntity.BodyBuilder builder = ResponseEntity.status(res.getStatusCode());
                    res.getHeaders().forEach((name, values) -> {
                        if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                            values.forEach(v -> builder.header(name, v));
                        }
                    });
                    return builder.body(raw);
                });
            // If the upstream (gateway/IdP) returned 401 because the SESSION's bearer token is
            // dead/expired, invalidate the BFF session so the client is forced to re-authenticate.
            // But a 401 from a plugin/auth hiccup MUST NOT destroy a LIVE session (E2E: a distributor
            // admin's session died mid-chain from an unrelated upstream 401 → NO_SESSION on the next
            // call). Only true token-rejection codes invalidate; anything else is logged and passed.
            if (upstream.getStatusCode().is4xxClientError() && upstream.getStatusCode().value() == 401) {
                String code = problemCode(new String(upstream.getBody(), java.nio.charset.StandardCharsets.UTF_8));
                if (TOKEN_REJECTION_CODES.contains(code)) {
                    log.warn("proxy_session_invalidated path={} code={}", request.getRequestURI(), code);
                    jakarta.servlet.http.HttpSession s = request.getSession(false);
                    if (s != null) tokens.end(s, "token_rejected");
                } else {
                    log.warn("proxy_upstream_401 path={} code={} sessionKept=true", request.getRequestURI(), code);
                }
            }
            writePassthrough(response, upstream);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // 401 from upstream: session invalidation follows the same token-rejection codes rule.
            if (e.getStatusCode().value() == 401) {
                String code = problemCode(e.getResponseBodyAsString());
                if (TOKEN_REJECTION_CODES.contains(code)) {
                    log.warn("proxy_session_invalidated path={} code={}", request.getRequestURI(), code);
                    jakarta.servlet.http.HttpSession s = request.getSession(false);
                    if (s != null) tokens.end(s, "token_rejected");
                } else {
                    log.warn("proxy_upstream_401 path={} code={} sessionKept=true", request.getRequestURI(), code);
                }
            }
            writeProblem(response, e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("proxy_failure path={} detail={}", request.getRequestURI(), e.getMessage(), e);
            reject(response, 502, "UPSTREAM_UNAVAILABLE", "Gateway service unavailable");
        }
    }

    private void copyTrusted(HttpServletRequest request, String method, String token, String dpopProof,
                             String actAsSessionId, org.springframework.http.HttpHeaders headers) {
        // Only neutral passthrough headers + Idempotency-Key survive; identity comes from the session token.
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name == null || NEVER_PASSED.contains(name.toLowerCase()) || HOP_BY_HOP.contains(name.toLowerCase())) {
                continue;
            }
            java.util.Collections.list(request.getHeaders(name)).forEach(v -> headers.add(name, v));
        }
        headers.setBearerAuth(token);
        if (dpopProof != null) {
            headers.set("DPoP", dpopProof);
        }
        if (StringUtils.hasText(actAsSessionId)) {
            headers.set("X-Act-As", actAsSessionId);
        }
        headers.set("X-FSP-Client-Id", "fsp-bff");
        if (!"GET".equals(method) && !StringUtils.hasText(request.getHeader("Idempotency-Key"))) {
            log.debug("proxy_mutation_without_idempotency — core will reject with IDEMPOTENCY_KEY_REQUIRED");
        }
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        if (!StringUtils.hasText(request.getHeader("Content-Length"))) return new byte[0];
        int max = props.getProxy().getMaxBodyBytes();
        try (var in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(max + 1);
            if (bytes.length > max) {
                log.warn("proxy_body_too_large path={} limit={}", request.getRequestURI(), max);
                return null;
            }
            return bytes;
        }
    }

    /** Best-effort RFC-7807 `code` extraction from an upstream problem body. */
    private static String problemCode(String body) {
        if (body == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"code\"\s*:\\s*\"([^\"]+)\"")
            .matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private static void writePassthrough(HttpServletResponse response, ResponseEntity<byte[]> upstream)
        throws IOException {
        response.setStatus(upstream.getStatusCode().value());
        upstream.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase()) && !"content-length".equals(name.toLowerCase())) {
                values.forEach(v -> response.addHeader(name, v));
            }
        });
        byte[] body = upstream.getBody() == null ? new byte[0] : upstream.getBody();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }

    private static void writeProblem(HttpServletResponse response, int status, String rawBody)
        throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(rawBody == null ? 0 : rawBody.length());
        if (rawBody != null) {
            response.getOutputStream().write(rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        response.getOutputStream().flush();
    }

    private static void reject(HttpServletResponse response, int status, String code, String detail)
        throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"code\":\"%s\",\"detail\":\"%s\",\"status\":%d}"
            .formatted(code, detail, status));
    }
}