package com.fsp.plugin.kyc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.plugin.kyc.api.ApiProblemException;
import com.fsp.plugin.kyc.security.MachineCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The plugin's ONLY way to core: through the gateway, as its machine client (plan §4.3/§4.4).
 * Every call carries "DPoP &lt;token&gt;" + a fresh DPoP proof; on-behalf calls (Path D) also
 * carry the gateway's actor assertion for the person, which core verifies itself. Core's
 * problem codes are passed through (never flattened into a generic 502).
 */
@Slf4j
@Component
public class CoreClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient rest = RestClient.create();
    private final String gatewayUrl;
    private final MachineCredentials credentials;

    public CoreClient(@Value("${fsp.core.gatewayUrl}") String gatewayUrl, MachineCredentials credentials) {
        this.gatewayUrl = gatewayUrl;
        this.credentials = credentials;
    }

    /** POST /v1/tenants/{parentId}/children — saga step: core creates the org atomically (DELEGATED). */
    public Map<String, Object> createChildTenant(UUID parentId, Map<String, Object> body,
                                                 String actorAssertion, String idempotencyKey) {
        return object(call(HttpMethod.POST, "/v1/tenants/" + parentId + "/children", body, actorAssertion, idempotencyKey));
    }

    /** POST /v1/approvals/plugin — open the four-eyes approval for the person (DELEGATED). */
    public Map<String, Object> openApproval(Map<String, Object> body, String actorAssertion, String idempotencyKey) {
        return object(call(HttpMethod.POST, "/v1/approvals/plugin", body, actorAssertion, idempotencyKey));
    }

    /** POST /v1/approvals/{id}/approve|reject — the checker resolves the approval (DELEGATED). */
    public Map<String, Object> resolveApproval(UUID approvalId, boolean approve, String reason,
                                               String actorAssertion, String idempotencyKey) {
        return object(call(HttpMethod.POST, "/v1/approvals/" + approvalId + "/" + (approve ? "approve" : "reject"),
            Map.of("reason", reason == null ? "" : reason), actorAssertion, idempotencyKey));
    }

    /** POST /v1/tenants/{subject}/verification — the verdict, backed by an APPROVED approval (MACHINE). */
    public Map<String, Object> recordVerification(UUID subjectTenantId, String verdict, UUID approvalId,
                                                  String payloadHash) {
        return object(call(HttpMethod.POST, "/v1/tenants/" + subjectTenantId + "/verification",
            Map.of("verdict", verdict, "approvalId", approvalId, "payloadHash", payloadHash),
            null, "kyc-verdict-" + approvalId));
    }

    /** V2 → core V36: POST /v1/tenants/{t}/customers — the customer person, PENDING (DELEGATED). */
    public Map<String, Object> createCustomer(UUID tenantId, Map<String, Object> body, String actorAssertion,
                                              String idempotencyKey) {
        return object(call(HttpMethod.POST, "/v1/tenants/" + tenantId + "/customers", body, actorAssertion, idempotencyKey));
    }

    /** GET /v1/tenants/{t}/customers/{p} — status + whether the contact is verified (DELEGATED). */
    public Map<String, Object> getCustomer(UUID tenantId, UUID personId, String actorAssertion) {
        return object(call(HttpMethod.GET, "/v1/tenants/" + tenantId + "/customers/" + personId, null,
            actorAssertion, null));
    }

    /** POST /v1/tenants/{t}/persons/{p}/activation — re-send link/code to the primary contact (DELEGATED). */
    public Map<String, Object> resendActivation(UUID tenantId, UUID personId, String actorAssertion,
                                                String idempotencyKey) {
        return object(call(HttpMethod.POST, "/v1/tenants/" + tenantId + "/persons/" + personId + "/activation",
            Map.of(), actorAssertion, idempotencyKey));
    }

    /** POST /v1/tenants/{t}/customers/{p}/verification — the verdict, backed by the approval (MACHINE). */
    public Map<String, Object> recordCustomerVerification(UUID tenantId, UUID personId, String verdict,
                                                          UUID approvalId, String payloadHash) {
        return object(call(HttpMethod.POST, "/v1/tenants/" + tenantId + "/customers/" + personId + "/verification",
            Map.of("verdict", verdict, "approvalId", approvalId, "payloadHash", payloadHash),
            null, "kyc-customer-verdict-" + approvalId));
    }

    /** GET /v1/tenants/{id}/subtree — the person's reach (DELEGATED). */
    public Map<String, Object> getSubtree(UUID tenantId, int page, int size, String sort, String actorAssertion) {
        return object(call(HttpMethod.GET, "/v1/tenants/" + tenantId + "/subtree?page=" + page + "&size=" + size
            + "&sort=" + sort, null, actorAssertion, null));
    }

    /** GET /v1/tenants/{id} — one organization, visible only if the PERSON could see it (DELEGATED). */
    public Map<String, Object> getTenant(UUID tenantId, String actorAssertion) {
        return object(call(HttpMethod.GET, "/v1/tenants/" + tenantId, null, actorAssertion, null));
    }

    /** GET /v1/me/permissions as the acting person (DELEGATED): their keys and the checker flag. */
    public Map<String, Object> me(String actorAssertion) {
        return object(call(HttpMethod.GET, "/v1/me/permissions", null, actorAssertion, null));
    }

    /** Installations of this plugin awaiting readiness (MACHINE). */
    public List<Map<String, Object>> provisioning(String pluginCode) {
        JsonNode n = call(HttpMethod.GET, "/v1/plugins/" + pluginCode + "/installations/provisioning", null, null, null);
        return MAPPER.convertValue(n, MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    /** Report one readiness gate for an installation (MACHINE). */
    public void reportReady(UUID installationId, String gate) {
        call(HttpMethod.POST, "/v1/plugins/installations/" + installationId + "/ready",
            Map.of("gate", gate, "status", "ACTIVE"), null, "kyc-ready-" + installationId + "-" + gate);
    }

    private JsonNode call(HttpMethod method, String pathAndQuery, Object body, String actorAssertion,
                          String idempotencyKey) {
        String url = gatewayUrl + pathAndQuery;
        String htu = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        try {
            String authorization = credentials.authorization(); // may refresh the token first
            var req = rest.method(method).uri(java.net.URI.create(url))
                .header("Authorization", authorization)
                .header("DPoP", credentials.proof(method.name(), htu));
            if (actorAssertion != null) req = req.header("X-FSP-Actor-Assertion", actorAssertion);
            if (idempotencyKey != null) req = req.header("Idempotency-Key", idempotencyKey);
            if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).body(body);
            String res = req.retrieve().body(String.class);
            return res == null || res.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(res);
        } catch (RestClientResponseException e) {
            JsonNode p = parse(e.getResponseBodyAsString());
            String code = p.path("code").asText(p.path("title").asText("CORE_ERROR"));
            String detail = p.path("detail").asText("Core refused the request");
            log.warn("core_call_refused method={} path={} status={} code={}", method, htu, e.getStatusCode().value(), code);
            int status = e.getStatusCode().value() >= 500 ? 502 : e.getStatusCode().value();
            throw new ApiProblemException(code, detail, status);
        } catch (ApiProblemException e) {
            throw e;
        } catch (Exception e) {
            log.warn("core_call_failed method={} path={} detail={}", method, htu, e.getMessage());
            throw new ApiProblemException("CORE_UNAVAILABLE", "The platform could not be reached", 502);
        }
    }

    private static JsonNode parse(String s) {
        try {
            return MAPPER.readTree(s == null || s.isBlank() ? "{}" : s);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(JsonNode n) {
        return MAPPER.convertValue(n, Map.class);
    }
}
