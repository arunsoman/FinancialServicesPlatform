package com.fsp.core.identity;

import com.fsp.core.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * Core's internal hop to the IdP for admin-initiated password reset (credential-gated).
 * Zero trust: the internal credential proves the hop origin; core's caller identity
 * is already established by the BFF session. The IdP owns the secret and the reset
 * token — core never sees or touches it.
 */
@Slf4j
@Component
public class IdpInternalClient {

    private final RestClient rest;
    private final String base;
    private final String internalCredential;

    public IdpInternalClient(@Value("${fsp.idp.base-url:http://localhost:8082}") String idpBaseUrl,
                             @Value("${fsp.idp.internal-credential:}") String internalCredential) {
        this.base = idpBaseUrl;
        this.internalCredential = internalCredential;
        this.rest = RestClient.create();
    }

    /**
     * Triggers an admin-initiated password reset for the person. The IdP dispatches
     * a one-time reset link to the person's email; the admin never sees the token or
     * the new password. Always succeeds from core's perspective — the IdP enforces
     * RFC 9700 enumeration resistance (silent no-op for unknown/inactive persons).
     */
    public void triggerPasswordReset(UUID personId) {
        try {
            rest.method(HttpMethod.POST)
                .uri(base + "/idp/internal/password-reset/" + personId)
                .header("X-FSP-Internal-Credential", internalCredential)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            log.info("idp_internal_reset_rejected status={} personId={}", e.getStatusCode().value(), personId);
            throw new ApiException(status != null ? status : HttpStatus.BAD_GATEWAY,
                "IDP_CALL_FAILED", "IdP rejected the reset call");
        }
    }

    /**
     * V36: asks the IdP to (re)send the customer's activation on their primary contact — a
     * one-time link by email or a one-time code by SMS. Core never sees the token or code.
     * Called AFTER the onboarding transaction committed (never inside a DB tx, AGENTS §2d).
     */
    public void triggerActivation(UUID personId) {
        try {
            rest.method(HttpMethod.POST)
                .uri(base + "/idp/internal/activation/" + personId)
                .header("X-FSP-Internal-Credential", internalCredential)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            log.warn("idp_internal_activation_rejected status={} personId={}", e.getStatusCode().value(), personId);
            throw new ApiException(status != null ? status : HttpStatus.BAD_GATEWAY,
                "IDP_CALL_FAILED", "IdP rejected the activation call");
        }
    }
}
