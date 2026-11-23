package com.fsp.gateway;

import com.fsp.shared.JwtService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.KeyFactory;
import java.security.spec.ECParameterSpec;
import java.security.spec.EllipticCurve;
import java.security.spec.ECFieldFp;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The edge enforcement point (guide §2): every request is stripped to a transport envelope,
 * authenticated against the IdP's JWKS, re-stamped with trusted internal identity, matched
 * against the operation catalog and only then forwarded. Client-supplied identity headers,
 * authorization state and forwarding chains NEVER reach an origin.
 * FAPI 2.0: Validates DPoP proofs on /v1/** endpoints (resource server requirement §5.3.4).
 * Plugin upstream routing + readiness (plugin-platform-plan P2).
 */
@Configuration
public class ForwardFilterConfig {

    @Bean
    public FilterRegistrationBean<ForwardFilter> forwardFilter(GatewayProperties props,
                                                               CatalogRouteTable catalog,
                                                               JwksHolder jwks,
                                                               com.fsp.shared.ActorAssertion assertions,
                                                               BrowserRateLimiter rateLimiter) {
        FilterRegistrationBean<ForwardFilter> reg =
            new FilterRegistrationBean<>(new ForwardFilter(props, catalog, jwks, assertions, rateLimiter));
        reg.addUrlPatterns("/v1/*", "/idp/*");
        reg.setOrder(1);
        reg.setDispatcherTypes(DispatcherType.REQUEST);
        return reg;
    }

    public static class ForwardFilter extends OncePerRequestFilter {
        private static final Logger log = LoggerFactory.getLogger(ForwardFilter.class);
        private static final Set<String> STRIP_REQUEST_HEADERS = Set.of(
            "host", "content-length", "connection", "cookie", "authorization",
            "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-forwarded-port",
            "x-fsp-user-id", "x-fsp-tenant-id", "x-fsp-username", "x-fsp-client-id",
            "x-fsp-internal-credential", "transfer-encoding");
        private static final Set<String> STRIP_RESPONSE_HEADERS = Set.of(
            "connection", "transfer-encoding", "server", "date", "content-length",
            "strict-transport-security", "access-control-allow-credentials",
            "access-control-allow-origin", "access-control-allow-headers",
            "access-control-allow-methods", "vary");
        private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

        private final GatewayProperties props;
        private final CatalogRouteTable catalog;
        private final JwksHolder jwks;
        private final org.springframework.web.client.RestClient rest;
        private final BrowserRateLimiter rateLimiter;
        private final java.util.concurrent.ConcurrentHashMap<String, Long> dpopReplayCache = new java.util.concurrent.ConcurrentHashMap<>();
        private final ObjectMapper mapper = new ObjectMapper();
        private final com.fsp.shared.ActorAssertion assertions;

        public ForwardFilter(GatewayProperties props, CatalogRouteTable catalog, JwksHolder jwks,
                             com.fsp.shared.ActorAssertion assertions, BrowserRateLimiter rateLimiter) {
            this.props = props;
            this.catalog = catalog;
            this.jwks = jwks;
            this.assertions = assertions;
            this.rateLimiter = rateLimiter;
            this.rest = org.springframework.web.client.RestClient.create();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            try {
                MDC.put("traceId", UUID.randomUUID().toString());
                // Edge rate limit per browser/client — reject burst before any token
                // validation or authorization decision (defense-in-depth, not a FAPI spacing rule).
                var verdict = rateLimiter.check(clientKey(request));
                if (!verdict.allowed()) {
                    log.warn("gateway_rate_limited path={} retryAfterMs={}",
                        request.getRequestURI(), verdict.retryAfter().toMillis());
                    problem(response, 429, "RATE_LIMITED",
                        "Too many requests; slow down and retry after " + verdict.retryAfter().toMillis() + " ms");
                    response.setHeader("Retry-After",
                        String.valueOf(Math.max(1, verdict.retryAfter().toSeconds())));
                    return;
                }
                handle(request, response);
            } catch (Exception e) {
                log.error("gateway_forward_failure method={} path={} detail={}",
                    request.getMethod(), request.getRequestURI(), e.getMessage(), e);
                problem(response, 502, "UPSTREAM_UNAVAILABLE", "Origin service unavailable");
            } finally {
                MDC.clear();
            }
        }

        private void handle(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
            String method = request.getMethod();
            String path = request.getRequestURI();
            if (!ALLOWED_METHODS.contains(method)) {
                problem(response, 405, "METHOD_NOT_ALLOWED", "method not forwarded by the gateway");
                return;
            }
            boolean coreSide = path.startsWith("/v1/");
            boolean idpSide = path.equals("/idp/jwks") || path.equals("/idp/token")
                || path.equals("/idp/introspect") || path.equals("/idp/par")
                || path.equals("/idp/authorize") || path.equals("/idp/authorize/callback")
                || path.equals("/idp/login") || path.equals("/idp/error")
                || path.equals("/idp/password/complete")
                || path.equals("/idp/activate")
                || path.equals("/idp/policy") || path.startsWith("/idp/policy/")
                || path.startsWith("/idp/.well-known");
            if (coreSide && idpSide) {
                problem(response, 400, "AMBIGUOUS_ROUTE", "path matches both route families");
                return;
            }
            if (!coreSide && !idpSide) {
                problem(response, 404, "ROUTE_NOT_IN_CATALOG", "no catalog route for " + path);
                return;
            }
            // Plugin routes come ONLY from REGISTERED manifests; core catalog routes always win.
            java.util.Optional<CatalogRouteTable.PluginOp> pluginOp =
                coreSide ? catalog.matchPlugin(method, path) : java.util.Optional.empty();
            // Platform-service routes (emi/psp) come from the services' own descriptors.
            java.util.Optional<CatalogRouteTable.ServiceOp> serviceOp =
                coreSide ? catalog.matchService(method, path) : java.util.Optional.empty();
            if (coreSide && pluginOp.isEmpty() && serviceOp.isEmpty() && !catalog.matches(method, path)) {
                log.warn("gateway_route_rejected method={} path={}", method, path);
                problem(response, 404, "ROUTE_NOT_IN_CATALOG",
                    "route is not a published FSP operation (catalog digest " + catalog.digest() + ")");
                return;
            }

            HttpHeaders upstream = new HttpHeaders();
            String authorization = request.getHeader("Authorization");
            String dpopHeader = request.getHeader("DPoP");

            if (coreSide) {
                // FAPI 2.0 Resource Server: sender-constrained token (DPoP) + token validity
                if (!validateDPoPProof(dpopHeader, authorization, request, response)) {
                    return; // Response already written
                }

                JwtService.Claims claims = authenticate(authorization, response);
                if (claims == null) {
                    return;
                }
                // Machine tokens (client_credentials) carry sub "client:<client_id>" and no person.
                boolean machine = claims.userId() != null && claims.userId().startsWith("client:");
                MDC.put("userId", machine ? claims.userId() : claims.userId());
                MDC.put("tenantId", claims.tenantId());
                log.info("gateway_forward method={} path={} user={} tenant={} plane={}",
                    method, path, claims.userId(), claims.tenantId(), machine ? "MACHINE" : "PERSON");
                String idem = request.getHeader("Idempotency-Key");
                String trace = request.getHeader("X-Correlation-Id");
                if (trace == null || trace.isBlank()) {
                    trace = UUID.randomUUID().toString();
                }

                if (pluginOp.isPresent()) {
                    // Path A — person → plugin: the token stops HERE (plan §4.1)
                    if (machine) {
                        log.warn("gateway_plugin_machine_rejected path={} client={}", path, claims.clientId());
                        problem(response, 403, "ACTOR_TYPE_REJECTED", "plugin operations are person-only");
                        return;
                    }
                    if (!admitToPlugin(pluginOp.get(), claims, method, path, trace, response, upstream,
                            request.getHeader("X-Act-As"))) {
                        return; // Response already written
                    }
                    if (idem != null) {
                        upstream.set("Idempotency-Key", idem);
                    }
                    upstream.set("X-Correlation-Id", trace);
                } else {
                    // Core: re-issue trusted internal identity (guide §2): claims → headers + internal credential.
                    // Platform-service ops (emi/psp) ride the same trusted re-issue, with one
                    // extension: a service op that DECLARED a permission key gets a core
                    // /internal/authz/decide first (core is the sole PDP, guide §3.5) — owner-
                    // scoped ops (empty permission) skip the RBAC hop and enforce ownership
                    // in-domain at the service (identity headers, act-as is not honoured here).
                    if (serviceOp.isPresent() && !serviceOp.get().permission().isBlank() && !machine) {
                        if (!decideService(serviceOp.get(), claims, trace, response, path)) return;
                    }
                    if (serviceOp.isPresent() && machine) {
                        log.warn("gateway_service_machine_rejected path={} op={}",
                            path, serviceOp.get().operationId());
                        problem(response, 403, "ACTOR_TYPE_REJECTED",
                            "platform-service operations are person-only");
                        return;
                    }
                    // Red-team P0 fix: per-target hop credentials — each upstream gets ITS OWN
                    // secret (core/EMI/PSP); the gateway holds only its own view of each.
                    upstream.set("X-FSP-Internal-Credential", targetCredential(serviceOp));
                    upstream.set("X-FSP-Tenant-Id", claims.tenantId());
                    upstream.set("X-FSP-Client-Id", claims.clientId());
                    if (machine) {
                        upstream.set("X-FSP-Actor-Type", "MACHINE");
                        // Path D — a plugin acting for a person presents the gateway's assertion;
                        // core verifies it (signature, aud plugin:<code>, budget) — never the gateway's word.
                        String actorAssertion = request.getHeader("X-FSP-Actor-Assertion");
                        if (actorAssertion != null && !actorAssertion.isBlank()) {
                            upstream.set("X-FSP-Actor-Assertion", actorAssertion);
                        }
                    } else {
                        upstream.set("X-FSP-User-Id", claims.userId());
                        upstream.set("X-FSP-Username", claims.username());
                        // Act-as (plans/act-as-tenant-plan.md D4): the opaque session id rides to core
                        // on the PERSON plane only. It is not an identity claim — core's ActAsFilter
                        // honours it only when the session belongs to the verified person above and
                        // is live, else 401 ACT_AS_EXPIRED (fail closed). Without this the BFF's id
                        // was dropped here and act-as never reached core (no ceiling, no audit trail).
                        String actAs = request.getHeader("X-Act-As");
                        if (actAs != null && !actAs.isBlank()) {
                            upstream.set("X-Act-As", actAs.trim());
                        }
                    }
                    if (idem != null) {
                        upstream.set("Idempotency-Key", idem);
                    }
                    upstream.set("X-Correlation-Id", trace);
                }
            } else {
                // /idp/** public surface: client-credential endpoints pass the auth headers THROUGH —
                // at the IdP the Authorization (private_key_jwt) and DPoP headers ARE the message.
                if (path.equals("/idp/token") || path.equals("/idp/par")) {
                    if (authorization != null) {
                        upstream.set("Authorization", authorization);
                    }
                    if (dpopHeader != null) {
                        upstream.set("DPoP", dpopHeader);
                    }
                } else if (path.startsWith("/idp/policy")) {
                    upstream.set("X-FSP-Internal-Credential", props.getIdpInternalCredential());
                    String bearerUser = bearerUserId(authorization);
                    if (bearerUser != null) {
                        upstream.set("X-FSP-User-Id", bearerUser);
                    }
                }
                log.info("gateway_forward method={} path={} surface=idp", method, path);
            }

            if (path.startsWith("/idp/.well-known")) {
                path = "/idp/.well-known/openid-configuration";
            }

            byte[] body = readBody(request);
            if (body == null) {
                problem(response, 413, "BODY_TOO_LARGE",
                    "body exceeds the gateway cap of " + props.getMaxBodyBytes() + " bytes");
                return;
            }

            // Plugin upstream routing: /v1/plugins/<code>/ routes forward to the plugin baseUrl;
            // /v1/emi/** (and later /v1/psp/**) routes forward to the owning service.
            String targetBase;
            if (coreSide) {
                targetBase = pluginOp.map(CatalogRouteTable.PluginOp::baseUrl).orElse(
                    serviceOp.map(CatalogRouteTable.ServiceOp::baseUrl).orElse(props.getCoreBaseUrl()));
            } else {
                targetBase = props.getIdpBaseUrl();
            }
            String target = targetBase + path
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());

            if (body.length > 0) {
                upstream.setContentType(MediaType.parseMediaType(request.getContentType() == null
                    ? MediaType.APPLICATION_JSON_VALUE : request.getContentType()));
            }
            ResponseEntity<byte[]> res;
            if (body.length == 0) {
                res = rest.method(HttpMethod.valueOf(method))
                    .uri(java.net.URI.create(target))
                    .headers(headers -> headers.addAll(upstream))
                    .exchange((req, rs) -> {
                        byte[] raw = rs.getBody().readAllBytes();
                        ResponseEntity.BodyBuilder builder = ResponseEntity.status(rs.getStatusCode());
                        rs.getHeaders().forEach((name, values) -> {
                            if (!STRIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                                values.forEach(v -> builder.header(name, v));
                            }
                        });
                        return builder.body(raw);
                    });
            } else {
                res = rest.method(HttpMethod.valueOf(method))
                    .uri(java.net.URI.create(target))
                    .headers(headers -> headers.addAll(upstream))
                    .body(body)
                    .exchange((req, rs) -> {
                        byte[] raw = rs.getBody().readAllBytes();
                        ResponseEntity.BodyBuilder builder = ResponseEntity.status(rs.getStatusCode());
                        rs.getHeaders().forEach((name, values) -> {
                            if (!STRIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                                values.forEach(v -> builder.header(name, v));
                            }
                        });
                        return builder.body(raw);
                    });
            }
            writeResponse(response, res);
        }

        /** FAPI 2.0 §5.3.4: Resource server MUST validate DPoP proof on protected endpoints. */
        private boolean validateDPoPProof(String dpopHeader, String authorization, HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (dpopHeader == null || dpopHeader.isBlank()) {
                log.warn("gateway_dpop_missing path={}", request.getRequestURI());
                problem(response, 401, "INVALID_DPOP_PROOF", "DPoP header required for FSP operations");
                return false;
            }

            String accessToken = tokenOf(authorization);
            boolean dpopScheme = authorization != null && authorization.startsWith("DPoP ");
            if (accessToken == null) {
                log.warn("gateway_dpop_no_token path={}", request.getRequestURI());
                problem(response, 401, "INVALID_DPOP_PROOF", "Bearer token required for DPoP validation");
                return false;
            }

            String jktFromToken = extractJktFromToken(accessToken);
            if (jktFromToken == null) {
                log.warn("gateway_dpop_no_cnf_jkt path={}", request.getRequestURI());
                problem(response, 401, "INVALID_DPOP_PROOF", "Access token missing cnf.jkt claim");
                return false;
            }

            String dpopJkt = validateDPoPProofInternal(dpopHeader, request, jktFromToken, response);
            if (dpopJkt == null) {
                return false; // Response already written
            }

            if (!jktFromToken.equals(dpopJkt)) {
                log.warn("gateway_dpop_jkt_mismatch token_jkt={} dpop_jkt={}", jktFromToken, dpopJkt);
                problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof JWK thumbprint does not match access token cnf.jkt");
                return false;
            }

            // RFC 9449 §4.3/§7.1: a proof presented WITH an access token binds it via ath.
            // Required under the DPoP scheme; checked whenever present.
            String ath = athOf(dpopHeader);
            if ((dpopScheme || ath != null) && !com.fsp.shared.Es256.sha256B64(accessToken).equals(ath)) {
                log.warn("gateway_dpop_ath_mismatch path={}", request.getRequestURI());
                problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof ath does not match the access token");
                return false;
            }

            return true;
        }

        private String validateDPoPProofInternal(String dpopHeader, HttpServletRequest request, String expectedJkt, HttpServletResponse response) throws IOException {
            String[] parts = dpopHeader.split("\\.");
            if (parts.length != 3) {
                log.warn("gateway_dpop_invalid_format");
                problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof format invalid");
                return null;
            }

            try {
                String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
                JsonNode header = mapper.readTree(headerJson);

                if (!"dpop+jwt".equals(header.path("typ").asText())) {
                    log.warn("gateway_dpop_invalid_typ");
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof typ must be 'dpop+jwt'");
                    return null;
                }
                if (!"ES256".equals(header.path("alg").asText()) && !"PS256".equals(header.path("alg").asText())) {
                    log.warn("gateway_dpop_invalid_alg");
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof alg must be ES256 or PS256");
                    return null;
                }

                String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
                JsonNode payload = mapper.readTree(payloadJson);

                String htu = payload.path("htu").asText();
                String expectedHtu = request.getRequestURL().toString();
                if (!expectedHtu.equals(htu)) {
                    log.warn("gateway_dpop_htu_mismatch expected={} actual={}", expectedHtu, htu);
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof htu mismatch");
                    return null;
                }

                String htm = payload.path("htm").asText();
                if (!request.getMethod().equalsIgnoreCase(htm)) {
                    log.warn("gateway_dpop_htm_mismatch expected={} actual={}", request.getMethod(), htm);
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof htm mismatch");
                    return null;
                }

                String jti = payload.path("jti").asText();
                if (jti == null || jti.isBlank()) {
                    log.warn("gateway_dpop_missing_jti");
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof missing jti");
                    return null;
                }
                Long existing = dpopReplayCache.putIfAbsent(jti, System.currentTimeMillis());
                if (existing != null) {
                    log.warn("gateway_dpop_replay_detected jti={}", jti);
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof replay detected");
                    return null;
                }
                if (dpopReplayCache.size() > 10000) {
                    long cutoff = System.currentTimeMillis() - 300000;
                    dpopReplayCache.entrySet().removeIf(e -> e.getValue() < cutoff);
                }

                long iat = payload.path("iat").asLong();
                long now = System.currentTimeMillis() / 1000;
                if (iat > now + 10 || iat < now - 300) {
                    log.warn("gateway_dpop_iat_out_of_range iat={} now={}", iat, now);
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof iat out of range");
                    return null;
                }

                JsonNode jwk = header.path("jwk");
                if (jwk.isMissingNode()) {
                    log.warn("gateway_dpop_missing_header_jwk");
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof missing header jwk");
                    return null;
                }
                if (!jwk.path("d").isMissingNode()) {
                    log.warn("gateway_dpop_private_key_in_jwk");
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof jwk contains private key material");
                    return null;
                }

                String jkt = computeJwkThumbprint(jwk);

                if (!verifyDPoPSignature(parts[0], parts[1], parts[2], jwk)) {
                    log.warn("gateway_dpop_signature_invalid");
                    problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof signature invalid");
                    return null;
                }

                return jkt;
            } catch (Exception e) {
                log.warn("gateway_dpop_validation_error", e);
                problem(response, 401, "INVALID_DPOP_PROOF", "DPoP proof validation failed: " + e.getMessage());
                return null;
            }
        }

        private String extractJktFromToken(String token) {
            try {
                String[] parts = token.split("\\.");
                if (parts.length != 3) return null;
                String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
                JsonNode payload = mapper.readTree(payloadJson);
                JsonNode cnf = payload.path("cnf");
                if (cnf.isMissingNode() || cnf.path("jkt").isMissingNode()) return null;
                return cnf.path("jkt").asText();
            } catch (Exception e) {
                return null;
            }
        }

        private String computeJwkThumbprint(JsonNode jwk) throws Exception {
            return com.fsp.shared.JwtService.jwkThumbprint(jwk);
        }

        private boolean verifyDPoPSignature(String headerB64, String payloadB64, String signatureB64, JsonNode jwk) throws Exception {
            return com.fsp.shared.JwtService.verifyEs256(jwk, headerB64 + "." + payloadB64, signatureB64);
        }

        /** Access token from "Bearer <t>" or "DPoP <t>" (RFC 6750 §2.1 / RFC 9449 §7.1) — header only. */
        private static String tokenOf(String authorization) {
            if (authorization == null) return null;
            if (authorization.startsWith("Bearer ")) return authorization.substring(7).trim();
            if (authorization.startsWith("DPoP ")) return authorization.substring(5).trim();
            return null;
        }

        private String athOf(String dpopProof) {
            try {
                JsonNode p = mapper.readTree(Base64.getUrlDecoder().decode(dpopProof.split("\\.")[1]));
                return p.hasNonNull("ath") ? p.get("ath").asText() : null;
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * Path A admission (plan §4.1): readiness (503 PLUGIN_NOT_READY) → core decides the
         * person's permission for THIS operation (core is the only PDP; never cached) → mint a
         * 60 s ES256 actor assertion bound to aud plugin:<code> + this method/path. The plugin
         * receives the assertion and nothing else that identifies the caller: no token, no
         * internal credential, no X-FSP-* identity.
         */
        private boolean admitToPlugin(CatalogRouteTable.PluginOp op, JwtService.Claims claims, String method,
                                      String path, String trace, HttpServletResponse response,
                                      HttpHeaders upstream, String actAsHeader) throws IOException {
            String tenantId = claims.tenantId();
            // Act-as (plans/act-as-tenant-plan.md): a person acting as a tenant works on plugins AS that
            // tenant. Core — the only owner of act-as sessions — resolves it for the VERIFIED person;
            // anything but a live session of theirs refuses the call (never falls back to home).
            String actSessionId = null;
            String homeTenantId = null;
            if (actAsHeader != null && !actAsHeader.isBlank()) {
                String sid = actAsHeader.trim();
                if (!sid.matches("[A-Za-z0-9-]{1,64}")) {
                    problem(response, 401, "ACT_AS_EXPIRED", "Your session acting as another tenant has ended");
                    return false;
                }
                try {
                    ResponseEntity<String> res = rest.method(HttpMethod.GET)
                        .uri(props.getCoreBaseUrl() + "/internal/act-as/" + sid)
                        .header("X-FSP-Internal-Credential", props.getInternalCredential())
                        .header("X-FSP-User-Id", claims.userId())
                        .header("X-FSP-Tenant-Id", claims.tenantId())
                        .header("X-FSP-Username", claims.username())
                        .header("X-FSP-Client-Id", claims.clientId())
                        .header("X-Correlation-Id", trace)
                        .retrieve().toEntity(String.class);
                    JsonNode live = mapper.readTree(res.getBody() == null ? "{}" : res.getBody());
                    if (!claims.userId().equals(live.path("personId").asText())
                        || live.path("targetTenantId").asText().isBlank()) {
                        problem(response, 401, "ACT_AS_EXPIRED", "Your session acting as another tenant has ended");
                        return false;
                    }
                    actSessionId = live.path("sessionId").asText();
                    homeTenantId = live.path("homeTenantId").asText();
                    tenantId = live.path("targetTenantId").asText();
                } catch (org.springframework.web.client.RestClientResponseException e) {
                    log.warn("gateway_plugin_act_as_refused path={} status={}", path, e.getStatusCode().value());
                    if (e.getStatusCode().value() == 401) {
                        problem(response, 401, "ACT_AS_EXPIRED", "Your session acting as another tenant has ended");
                    } else {
                        problem(response, 503, "ACT_AS_UNAVAILABLE", "Act-as session check unavailable");
                    }
                    return false;
                } catch (Exception e) {
                    log.warn("gateway_plugin_act_as_error path={} detail={}", path, e.getMessage());
                    problem(response, 503, "ACT_AS_UNAVAILABLE", "Act-as session check unavailable");
                    return false;
                }
            }
            try {
                ResponseEntity<String> ready = rest.method(HttpMethod.GET)
                    .uri(props.getCoreBaseUrl() + "/internal/plugins/" + op.code() + "/effective?tenantId=" + tenantId)
                    .header("X-FSP-Internal-Credential", props.getInternalCredential())
                    .retrieve().toEntity(String.class);
                if (ready.getBody() == null || !mapper.readTree(ready.getBody()).path("applies").asBoolean(false)) {
                    log.warn("gateway_plugin_not_ready path={} plugin={} tenant={}", path, op.code(), tenantId);
                    problem(response, 503, "PLUGIN_NOT_READY",
                        "Plugin " + op.code() + " is not enabled for this organization");
                    return false;
                }
            } catch (Exception e) {
                log.warn("gateway_plugin_readiness_error path={} plugin={} detail={}", path, op.code(), e.getMessage());
                problem(response, 503, "PLUGIN_NOT_READY", "Plugin readiness check unavailable");
                return false;
            }

            JsonNode decision;
            try {
                Map<String, Object> ask = new java.util.LinkedHashMap<>(Map.of(
                    "permission", op.permission(), "tenantId", tenantId, "operationId", op.operationId()));
                if (actSessionId != null) ask.put("actSessionId", actSessionId);
                String body = mapper.writeValueAsString(ask);
                ResponseEntity<String> res = rest.method(HttpMethod.POST)
                    .uri(props.getCoreBaseUrl() + "/internal/authz/decide")
                    .header("X-FSP-Internal-Credential", props.getInternalCredential())
                    .header("X-FSP-User-Id", claims.userId())
                    .header("X-FSP-Tenant-Id", claims.tenantId())
                    .header("X-FSP-Username", claims.username())
                    .header("X-FSP-Client-Id", claims.clientId())
                    .header("X-Correlation-Id", trace)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().toEntity(String.class);
                decision = mapper.readTree(res.getBody() == null ? "{}" : res.getBody());
            } catch (Exception e) {
                log.error("gateway_authz_unavailable path={} op={} detail={}", path, op.operationId(), e.getMessage());
                problem(response, 503, "AUTHZ_UNAVAILABLE", "Authorization decision unavailable");
                return false;
            }
            if (!decision.path("allow").asBoolean(false)) {
                String reason = decision.path("reason").asText("PERMISSION_DENIED");
                log.warn("gateway_plugin_denied path={} op={} permission={} reason={}",
                    path, op.operationId(), op.permission(), reason);
                response.setStatus(403);
                response.setContentType("application/problem+json");
                response.getWriter().write(mapper.writeValueAsString(Map.of(
                    "status", 403, "code", reason, "title", reason, "permission", op.permission(),
                    "detail", "This needs the " + op.permission() + " permission on your organization.",
                    "correlationId", trace)));
                return false;
            }

            String assertion = assertions.mint(new com.fsp.shared.ActorAssertion.Subject(
                    claims.userId(), tenantId, claims.username(), claims.clientId(), op.operationId(),
                    op.permission(), tenantId, trace, actSessionId, homeTenantId),
                "plugin:" + op.code(), method, path);
            upstream.set("X-FSP-Actor-Assertion", assertion);
            log.info("gateway_plugin_admitted plugin={} op={} person={} tenant={} actAs={}",
                op.code(), op.operationId(), claims.userId(), tenantId, actSessionId != null);
            return true;
        }

        /**
         * Core PDP decision for a permission-carrying platform-service op (guide §3.5):
         * same contract as the plugin decide (uncached, revocation hits the next call).
         * Owner-scoped ops (blank permission) never reach this — their resource ownership
         * is enforced by the service itself against the verified identity headers.
         */
        private boolean decideService(CatalogRouteTable.ServiceOp op, JwtService.Claims claims,
                                      String trace, HttpServletResponse response, String requestPath) throws IOException {
            JsonNode decision;
            try {
                Map<String, Object> ask = new java.util.LinkedHashMap<>(Map.of(
                    "permission", op.permission(), "tenantId", claims.tenantId(),
                    "operationId", op.operationId()));
                ResponseEntity<String> res = rest.method(HttpMethod.POST)
                    .uri(props.getCoreBaseUrl() + "/internal/authz/decide")
                    .header("X-FSP-Internal-Credential", props.getInternalCredential())
                    .header("X-FSP-User-Id", claims.userId())
                    .header("X-FSP-Tenant-Id", claims.tenantId())
                    .header("X-FSP-Username", claims.username())
                    .header("X-FSP-Client-Id", claims.clientId())
                    .header("X-Correlation-Id", trace)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(ask))
                    .retrieve().toEntity(String.class);
                decision = mapper.readTree(res.getBody() == null ? "{}" : res.getBody());
            } catch (Exception e) {
                log.error("gateway_authz_unavailable path={} op={} detail={}", requestPath, op.operationId(), e.getMessage());
                problem(response, 503, "AUTHZ_UNAVAILABLE", "Authorization decision unavailable");
                return false;
            }
            if (!decision.path("allow").asBoolean(false)) {
                String reason = decision.path("reason").asText("PERMISSION_DENIED");
                log.warn("gateway_service_denied path={} op={} permission={} reason={}",
                    requestPath, op.operationId(), op.permission(), reason);
                response.setStatus(403);
                response.setContentType("application/problem+json");
                response.getWriter().write(mapper.writeValueAsString(Map.of(
                    "status", 403, "code", reason, "title", reason, "permission", op.permission(),
                    "detail", "This needs the " + op.permission() + " permission on your organization.")));
                return false;
            }
            return true;
        }

        private JwtService.Claims authenticate(String authorization, HttpServletResponse response)
            throws IOException {
            String code;
            String token = tokenOf(authorization);
            if (token == null) {
                code = "TOKEN_MISSING";
            } else {
                var claims = jwks.verifier(props.getIssuer()).verify(
                    token, jwks.jwks(), props.getIssuer(), props.getAudience());
                if (claims != null) {
                    return claims;
                }
                code = "TOKEN_INVALID";
            }
            log.warn("gateway_auth_rejected code={}", code);
            problem(response, 401, code,
                code.equals("TOKEN_MISSING")
                    ? "Bearer token required for FSP operations"
                    : "token failed signature, audience, issuer or freshness checks");
            return null;
        }

        private String bearerUserId(String authorization) {
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return null;
            }
            var claims = jwks.verifier(props.getIssuer()).verify(
                authorization.substring(7).trim(), jwks.jwks(), props.getIssuer(), props.getAudience());
            return claims == null ? null : claims.userId();
        }

        /**
         * Derive a stable, per-browser rate-limit key. The browser holds only the BFF
         * session cookie (tokens never enter the browser), so the cookie is the best
         * client handle; fall back to client IP. The value is SHA-256 hashed so the
         * raw cookie / IP is never stored or logged (AGENTS.md §1.5).
         */
        private String clientKey(HttpServletRequest request) {
            String cookie = request.getHeader("Cookie");
            String xff = request.getHeader("X-Forwarded-For");
            String remote = request.getRemoteAddr();
            String source = (cookie != null && !cookie.isBlank()) ? cookie :
                (xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : remote);
            return source == null ? "unknown" : com.fsp.shared.Es256.sha256B64(source);
        }

        private byte[] readBody(HttpServletRequest request) throws IOException {
            long cap = props.getMaxBodyBytes();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            try (var in = request.getInputStream()) {
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > cap) {
                        return null;
                    }
                    out.write(buffer, 0, read);
                }
            }
            return out.toByteArray();
        }

        private void writeResponse(HttpServletResponse response, ResponseEntity<byte[]> upstream)
            throws IOException {
            response.setStatus(upstream.getStatusCode().value());
            if (upstream.getHeaders() != null) {
                upstream.getHeaders().forEach((name, values) -> {
                    if (!STRIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                        response.addHeader(name, String.join(", ", values));
                    }
                });
            }
            byte[] body = upstream.getBody();
            if (body != null && body.length > 0) {
                response.getOutputStream().write(body);
            }
        }

        private void problem(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":\"" + code + "\",\"detail\":\""
                + detail.replace("\"", "'") + "\",\"status\":" + status + "}");
        }

        private String pluginCodeFromPath(String path) {
            String prefix = "/v1/plugins/";
            int start = prefix.length();
            int end = path.indexOf('/', start);
            if (end < 0) end = path.length();
            return path.substring(start, end);
        }

        /** Per-target internal-hop credential (red-team P0 fix): the gateway never holds one
         *  god-key — core ops stamp core's secret, EMI ops EMI's, PSP ops PSP's; unknown
         *  upstreams get the core secret (they are core-routed by construction). */
        private String targetCredential(java.util.Optional<CatalogRouteTable.ServiceOp> serviceOp) {
            return serviceOp.map(op -> switch (op.component()) {
                case "emi" -> props.getEmiInternalCredential();
                case "psp" -> props.getPspInternalCredential();
                default -> props.getInternalCredential();
            }).orElse(props.getInternalCredential());
        }
    }
}
