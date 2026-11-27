package com.fsp.plugin.inventory.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.shared.ActorAssertion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every inbound /v1/plugins/inventory/** request MUST carry the gateway's actor assertion
 * (plan §4.1, §10): verified against the gateway's published key, addressed to
 * plugin:kyc, bound to THIS method + path, single-use. The verified person is the only
 * identity the plugin ever uses — no other header is trusted (X-FSP-* is ignored).
 * The raw assertion is kept for on-behalf calls to core (Path D).
 */
@Slf4j
@Component
@Order(1)
public class ActorAssertionFilter extends OncePerRequestFilter {

    public static final String ATTR = "inventory.actor";
    private static final String HEADER = "X-FSP-Actor-Assertion";

    private final ActorAssertion verifier;
    private final String audience;
    private final ObjectMapper mapper = new ObjectMapper();

    public ActorAssertionFilter(@Value("${fsp.core.gatewayUrl}") String gatewayUrl,
                                @Value("${fsp.plugin.code}") String code) {
        this.audience = "plugin:" + code;
        GatewayKeys keys = new GatewayKeys(gatewayUrl + "/.well-known/fsp-actor-jwks");
        this.verifier = ActorAssertion.verifier(keys::resolve, 1);
    }

    /** The verified caller of this request. */
    public record PluginActor(UUID personId, UUID tenantId, String username, String operationId,
                              String correlationId, String assertion) {}

    public static PluginActor actor(HttpServletRequest request) {
        return (PluginActor) request.getAttribute(ATTR);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/plugins/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var va = verifier.verify(request.getHeader(HEADER), audience, request.getMethod(), request.getRequestURI());
            PluginActor actor = new PluginActor(UUID.fromString(va.personId()), UUID.fromString(va.tenantId()),
                va.username(), va.operationId(), va.correlationId(), request.getHeader(HEADER));
            request.setAttribute(ATTR, actor);
            MDC.put("traceId", va.correlationId());
            MDC.put("userId", va.personId());
            MDC.put("tenantId", va.tenantId());
            chain.doFilter(request, response);
        } catch (ActorAssertion.Rejected e) {
            log.warn("plugin_assertion_rejected code={} method={} path={}", e.code(), request.getMethod(), request.getRequestURI());
            response.setStatus(e.code().equals("ASSERTION_REPLAY") ? 403 : 401);
            response.setContentType("application/problem+json");
            response.getWriter().write(mapper.writeValueAsString(Map.of(
                "status", response.getStatus(), "code", e.code(), "title", e.code(),
                "detail", "The request did not carry a valid gateway actor assertion.")));
        } catch (IllegalArgumentException e) {
            log.warn("plugin_assertion_subject_invalid path={}", request.getRequestURI());
            response.setStatus(401);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":401,\"code\":\"ASSERTION_INVALID\",\"title\":\"ASSERTION_INVALID\"}");
        } finally {
            MDC.clear();
        }
    }

    /** kid → JWK over the gateway's published actor JWKS (refetched on unknown kid, ≤ 1/30 s). */
    static final class GatewayKeys {
        private final String url;
        private final RestClient rest = RestClient.create();
        private final ObjectMapper mapper = new ObjectMapper();
        private final Map<String, JsonNode> byKid = new ConcurrentHashMap<>();
        private volatile long lastFetch;

        GatewayKeys(String url) {
            this.url = url;
        }

        JsonNode resolve(String kid) {
            JsonNode k = byKid.get(kid);
            if (k != null) return k;
            long now = System.currentTimeMillis();
            if (now - lastFetch < 30_000 && !byKid.isEmpty()) return null;
            lastFetch = now;
            try {
                mapper.readTree(rest.get().uri(url).retrieve().body(String.class))
                    .path("keys").forEach(j -> byKid.put(j.path("kid").asText(), j));
            } catch (Exception e) {
                log.warn("plugin_actor_jwks_fetch_failed url={} detail={}", url, e.getMessage());
            }
            return byKid.get(kid);
        }
    }
}
