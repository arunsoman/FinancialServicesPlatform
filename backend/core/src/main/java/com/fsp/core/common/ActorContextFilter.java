package com.fsp.core.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Core admission filter:
 *  - validates the internal credential on all /v1 and /internal routes (BFF is the only caller),
 *  - extracts trusted identity headers set by the BFF,
 *  - seeds correlation context for audit AND the logging MDC (traceId/tenantId/userId, AGENTS.md §2c),
 *  - incident-logs every admission failure (negative path).
 * Phase-0: permission enforcement happens in services via PermissionService (per-endpoint
 * {@code @FspOperation}-style catalog enforcement arrives with the hardening slice).
 */
@Slf4j
public class ActorContextFilter extends OncePerRequestFilter {

    private static final String INTERNAL_CREDENTIAL = "X-FSP-Internal-Credential";
    private static final String USER_ID = "X-FSP-User-Id";
    private static final String TENANT_ID = "X-FSP-Tenant-Id";
    private static final String USERNAME = "X-FSP-Username";
    private static final String CLIENT_ID = "X-FSP-Client-Id";
    private static final String ACTOR_TYPE = "X-FSP-Actor-Type";
    private static final String ACTOR_ASSERTION = "X-FSP-Actor-Assertion";

    /** Path D: resolves a plugin's machine call + gateway actor assertion to a DELEGATED actor. */
    public interface Delegation {
        /** @throws ApiException when the assertion is not acceptable for this client */
        Actor delegate(Actor machine, String assertion);
    }

    private final String expectedCredential;
    private final Delegation delegation;

    public ActorContextFilter(String expectedCredential) {
        this(expectedCredential, null);
    }

    public ActorContextFilter(String expectedCredential, Delegation delegation) {
        this.expectedCredential = expectedCredential;
        this.delegation = delegation;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean protectedPath = path.startsWith("/v1/") || path.startsWith("/internal/");
        if (protectedPath) {
            // Fail-closed: an unconfigured credential (blank) closes the internal plane entirely.
            if (expectedCredential == null || expectedCredential.isBlank()) {
                log.error("internal_plane_closed path={} remote={} reason=credential_not_configured",
                    path, request.getRemoteAddr());
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"code\":\"INTERNAL_PLANE_CLOSED\",\"detail\":\"Internal credential not configured\"}");
                return;
            }
            String credential = request.getHeader(INTERNAL_CREDENTIAL);
            if (credential == null || credential.isBlank() || !constantTimeEquals(credential, expectedCredential)) {
                log.warn("internal_credential_invalid path={} remote={} state={}",
                    path, request.getRemoteAddr(),
                    credential == null ? "missing" : "mismatch");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"code\":\"INTERNAL_CREDENTIAL_INVALID\",\"detail\":\"Missing or invalid internal credential\"}");
                return;
            }
        }

        try {
            String correlation = request.getHeader("X-Correlation-Id");
            if (correlation == null || correlation.isBlank()) correlation = UUID.randomUUID().toString();
            correlation += ":" + path; // unique per request+hop, per audit guidance

            String userIdH = request.getHeader(USER_ID);
            String tenantH = request.getHeader(TENANT_ID);

            Actor actor = null;
            String actorTypeH = request.getHeader(ACTOR_TYPE);
            Actor.ActorType type = actorTypeH == null ? Actor.ActorType.PERSON
                : switch (actorTypeH.toUpperCase()) {
                    case "MACHINE" -> Actor.ActorType.MACHINE;
                    case "DELEGATED" -> Actor.ActorType.DELEGATED;
                    default -> Actor.ActorType.PERSON;
                };

            try {
                if (type == Actor.ActorType.MACHINE) {
                    // Machine actor: personId null, clientId from header, tenant from header
                    String clientH = request.getHeader(CLIENT_ID);
                    if (tenantH == null || clientH == null) {
                        log.warn("actor_headers_invalid path={} machine actor missing tenant/client", path);
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"code\":\"ACTOR_HEADERS_INVALID\",\"detail\":\"Machine actor requires tenant and client id\"}");
                        return;
                    }
                    actor = new Actor(null, null, UUID.fromString(tenantH), Actor.ActorType.MACHINE, clientH);
                    String assertion = request.getHeader(ACTOR_ASSERTION);
                    if (assertion != null && !assertion.isBlank()) {
                        if (delegation == null) {
                            writeProblem(response, 401, "ASSERTION_UNSUPPORTED", "on-behalf calls are not enabled");
                            return;
                        }
                        try {
                            actor = delegation.delegate(actor, assertion);
                        } catch (ApiException e) {
                            writeProblem(response, e.getStatus().value(), e.getCode(), e.getMessage());
                            return;
                        }
                    }
                } else if (userIdH != null && tenantH != null) {
                    String clientH = request.getHeader(CLIENT_ID);
                    actor = new Actor(UUID.fromString(userIdH), request.getHeader(USERNAME), UUID.fromString(tenantH),
                        type, clientH);
                }
            } catch (IllegalArgumentException e) {
                log.warn("actor_headers_invalid path={} userId={} tenantId={} type={}", path, userIdH, tenantH, type);
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"ACTOR_HEADERS_INVALID\",\"detail\":\"Malformed identity headers\"}");
                return;
            }

            RequestCorrelation.setContext(correlation, request.getRemoteAddr(),
                request.getHeader(CLIENT_ID) == null ? "unknown" : request.getHeader(CLIENT_ID));
            RequestCorrelation.setActor(actor);
            bindMdc(correlation, actor);

            if (log.isDebugEnabled() && actor != null) {
                log.debug("actor_resolved path={} actor={}", path, actor.username());
            }

            if (actor != null) request.setAttribute("fsp.actor", actor);
            request.setAttribute("fsp.correlation", correlation);
            response.setHeader("X-Correlation-Id", correlation);

            chain.doFilter(request, response);
        } finally {
            MDC.clear();
            RequestCorrelation.clear();
        }
    }

    private static void writeProblem(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }

    /** Constant-time comparison — never leak the secret through response timing. */
    private static boolean constantTimeEquals(String given, String expected) {
        return java.security.MessageDigest.isEqual(
            given.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void bindMdc(String correlation, Actor actor) {
        // correlation is "<uuid>:<path>" — the uuid prefix is the per-hop trace id
        MDC.put("traceId", correlation.substring(0, correlation.indexOf(':')));
        if (actor != null) {
            MDC.put("tenantId", actor.activeTenantId().toString());
            if (actor.personId() != null) {
                MDC.put("userId", actor.personId().toString());
            }
            if (actor.clientId() != null) {
                MDC.put("clientId", actor.clientId());
            }
        }
    }
}
