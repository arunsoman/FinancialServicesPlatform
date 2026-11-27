package com.fsp.idp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Edge discipline for the IdP's own surface (AGENTS.md §4c):
 *  - policy management (/idp/policy*) is an internal hop: requires the internal credential;
 *  - inbound identity headers are never trusted (identity derives from credentials/tokens);
 *  - machine-parsable correlation (traceId) per §2c.
 */
@Component
@Order(1)
public class IdpCredentialFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdpCredentialFilter.class);

    private final IdpProperties props;

    public IdpCredentialFilter(IdpProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            MDC.put("traceId", UUID.randomUUID().toString());
            String path = request.getRequestURI();
            if (path.startsWith("/idp/policy")) {
                String credential = request.getHeader("X-FSP-Internal-Credential");
                if (props.getInternalCredential() == null || props.getInternalCredential().isBlank()
                    || credential == null || !constantTimeEquals(credential, props.getInternalCredential())) {
                    log.warn("idp_internal_hop_rejected path={}", path);
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"code\":\"INTERNAL_CREDENTIAL_REQUIRED\",\"detail\":\"policy management is not a public surface\",\"status\":401}");
                    return;
                }
                String userId = request.getHeader("X-FSP-User-Id");
                if (userId != null) {
                    MDC.put("userId", userId);
                }
            }
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
    /** Constant-time comparison — never leak the secret through response timing. */
    private static boolean constantTimeEquals(String given, String expected) {
        return java.security.MessageDigest.isEqual(
            given.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
