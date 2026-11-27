package com.fsp.core.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Core's ONLY way to the EMI money hands (plan §7.1): internal hop with the shared
 * internal credential + minted identity headers. Zero trust (AGENTS.md §1.4): the
 * credential proves the hop origin; the identity headers carry the acting person.
 * EMI's RFC 7807 codes are passed through — never flattened into a generic 502.
 * No tokens, credentials, or full account references are ever logged (§1.5).
 */
@Slf4j
@Component
public class EmiMoneyClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient rest;
    private final String base;
    private final String internalCredential;

    public EmiMoneyClient(@Value("${fsp.orders.emi-base-url}") String emiBaseUrl,
                          @Value("${fsp.security.emi-internal-credential}") String internalCredential) {
        this.base = emiBaseUrl;
        this.internalCredential = internalCredential;
        this.rest = RestClient.create();
    }

    /** Places the linked pending charge chain on the EMI wallet book. Returns the charge id. */
    public UUID charge(UUID walletId, UUID buyerPersonId, UUID tenantId, String username,
                       String orderRef, BigDecimal merchMinor, BigDecimal feeMinor,
                       BigDecimal taxMinor, String currency, String priceFingerprint) {
        JsonNode body = call(HttpMethod.POST, "/v1/emi/wallets/" + walletId + "/charges",
            buyerPersonId, tenantId, username, Map.of(
                "orderRef", orderRef,
                "merchMinor", merchMinor,
                "feeMinor", feeMinor,
                "taxMinor", taxMinor,
                "currency", currency,
                "priceFingerprint", priceFingerprint));
        return UUID.fromString(body.get("id").asText());
    }

    /** Resolving post-pending commit (fulfilment confirmed downstream of the charge). */
    public void confirm(UUID chargeId, UUID buyerPersonId, UUID tenantId, String username) {
        call(HttpMethod.POST, "/v1/emi/charges/" + chargeId + "/confirm",
            buyerPersonId, tenantId, username, null);
    }

    /** Resolving void-pending release (pre-fulfilment cancel). */
    public void voidCharge(UUID chargeId, UUID buyerPersonId, UUID tenantId, String username) {
        call(HttpMethod.POST, "/v1/emi/charges/" + chargeId + "/void",
            buyerPersonId, tenantId, username, null);
    }

    /** Charge status read for the sync op (EMI is the money truth for the legs). */
    public JsonNode getCharge(UUID chargeId, UUID buyerPersonId, UUID tenantId, String username) {
        return call(HttpMethod.GET, "/v1/emi/charges/" + chargeId,
            buyerPersonId, tenantId, username, null);
    }

    /**
     * The acting person's own NAD wallet ({id, balance, status, …}); empty when they have none.
     * Any other EMI refusal or a transport failure propagates — callers decide how to degrade.
     */
    public java.util.Optional<JsonNode> myWallet(UUID personId, UUID tenantId, String username) {
        try {
            return java.util.Optional.ofNullable(call(HttpMethod.GET, "/v1/emi/wallets/mine",
                personId, tenantId, username, null));
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) return java.util.Optional.empty();
            throw e;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private JsonNode call(HttpMethod method, String path, UUID personId, UUID tenantId,
                          String username, Object body) {
        try {
            var spec = rest.method(method).uri(base + path)
                .header("X-FSP-Internal-Credential", internalCredential)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", username != null ? username : "system");
            if (body != null) {
                spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
            }
            return spec.retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
    }

    /** Problem details from EMI keep their code (EMI_WALLET_NOT_FOUND, EMI_TB_REJECTED, ...). */
    private ApiException translate(RestClientResponseException e) {
        String code = "EMI_CALL_FAILED";
        String detail = "EMI rejected the money call (" + e.getStatusCode() + ")";
        try {
            JsonNode problem = MAPPER.readTree(e.getResponseBodyAsString());
            if (problem != null) {
                if (problem.path("code").isTextual()) code = problem.get("code").asText();
                if (problem.path("detail").isTextual()) detail = problem.get("detail").asText();
                else if (problem.path("title").isTextual()) detail = problem.get("title").asText();
            }
        } catch (Exception ignored) {
            // opaque body — keep generic mapping above; never log the raw body (may hold refs)
        }
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        log.info("emi_money_call_rejected status={} code={}", e.getStatusCode().value(), code);
        return new ApiException(status != null ? status : HttpStatus.BAD_GATEWAY, code, detail);
    }
}
