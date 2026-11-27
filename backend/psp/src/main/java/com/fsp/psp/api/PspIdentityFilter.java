package com.fsp.psp.api;

import com.fsp.psp.common.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Zero-trust edge for the PSP internal face (platform AGENTS §1.2/§1.4): every request on
 * /v1/** must arrive from the gateway with {@code X-FSP-Internal-Credential}; the trusted
 * identity headers are ONLY believed when that credential proves the origin. Person plane
 * only — machine tokens (sub {@code client:<id>}) are refused; a machine that later needs
 * this plane presents a signed actor assertion (plugin-platform-plan §6) and the filter
 * grows an assertion check, never a header override.
 *
 * /internal/** only requires the credential (the gateway's catalog fetch authenticates
 * nothing else).
 */
public class PspIdentityFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTR = "fsp.actor";

    private final String credential;
    private final MessageDigest sha = digestHolder();

    public PspIdentityFilter(String credential) {
        this.credential = credential == null ? "" : credential;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            requireCredential(request);
            if (request.getRequestURI().startsWith("/v1/")) {
                request.setAttribute(ACTOR_ATTR, person(request));
            }
            chain.doFilter(request, response);
        } catch (ApiException e) {
            problem(response, e.getStatus(), e.getCode(), e.getMessage());
        }
    }

    private void requireCredential(HttpServletRequest request) {
        // Fail-closed: an unconfigured credential closes the internal plane.
        if (credential == null || credential.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_PLANE_CLOSED",
                "internal credential not configured");
        }
        String given = request.getHeader("X-FSP-Internal-Credential");
        if (given == null || !constantTimeEquals(given, credential)) {
            // no log of the presented value — only that the hop failed provenance
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNTRUSTED_ORIGIN",
                "requests reach PSP only through the API gateway");
        }
    }

    private PspActor person(HttpServletRequest request) {
        String userId = required(request, "X-FSP-User-Id");
        String tenantId = required(request, "X-FSP-Tenant-Id");
        String username = required(request, "X-FSP-Username");
        String clientId = required(request, "X-FSP-Client-Id");
        if (userId.startsWith("client:")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACTOR_TYPE_REJECTED",
                "PSP operations are person-plane only");
        }
        try {
            return new PspActor(UUID.fromString(userId), UUID.fromString(tenantId), username, clientId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACTOR_MALFORMED",
                "identity headers failed validation");
        }
    }

    private static String required(HttpServletRequest request, String header) {
        String v = request.getHeader(header);
        if (v == null || v.isBlank() || v.length() > 128) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACTOR_MISSING",
                "missing verified identity header " + header);
        }
        return v;
    }

    private boolean constantTimeEquals(String given, String expected) {
        // digest both sides so comparison time does not encode the expected secret length
        byte[] g = sha.digest(given.getBytes(StandardCharsets.UTF_8));
        sha.reset();
        byte[] e = sha.digest(expected.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(g, e);
    }

    private static MessageDigest digestHolder() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    private static void problem(HttpServletResponse response, HttpStatus status,
                                String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write(
            "{\"status\":%d,\"code\":\"%s\",\"title\":\"%s\",\"detail\":\"%s\"}"
                .formatted(status.value(), code, code, detail == null ? "" : detail));
    }
}
