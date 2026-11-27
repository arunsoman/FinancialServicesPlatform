package com.fsp.plugin.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.plugin.inventory.api.ApiProblemException;
import com.fsp.plugin.inventory.security.MachineCredentials;
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
 *
 * Money surface (plans/inventory-plugin-plan.md D3/D4):
 *  - four-eyes journal entries (stock receipts, float funding): JE create/submit as the MAKER,
 *    approve as the CHECKER — core re-checks maker≠checker at real person_id;
 *  - posting intents (voucher dispense): single-actor immediate posting, same money invariants;
 *  - `ledger.coa.ensure` (MACHINE): additive, idempotent GL rows at readiness.
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

    /** GET /v1/tenants/{id}/books — GL account resolution needs the tenant's book (DELEGATED). */
    public JsonNode listBooks(UUID tenantId, String actorAssertion) {
        return call(HttpMethod.GET, "/v1/tenants/" + tenantId + "/books", null, actorAssertion, null);
    }

    /** GET /v1/tenants/{id}/books/{book}/gl-accounts — resolve GL rows BY CODE (DELEGATED). */
    public JsonNode listGlAccounts(UUID tenantId, UUID bookId, String actorAssertion) {
        return call(HttpMethod.GET, "/v1/tenants/" + tenantId + "/books/" + bookId + "/gl-accounts",
            null, actorAssertion, null);
    }

    /** POST .../gl-accounts/ensure — MACHINE: additive, idempotent CoA rows (readiness step). */
    public JsonNode ensureGlAccounts(UUID tenantId, UUID bookId, List<Map<String, Object>> rows) {
        return call(HttpMethod.POST, "/v1/tenants/" + tenantId + "/books/" + bookId + "/gl-accounts/ensure",
            rows, null, "inv-coa-ensure-" + tenantId + "-" + bookId);
    }

    /** POST /v1/tenants/{id}/journal-entries — the MAKER drafts the JE (DELEGATED). */
    public JsonNode createJeDraft(UUID tenantId, Map<String, Object> body, String actorAssertion, String key) {
        return call(HttpMethod.POST, "/v1/tenants/" + tenantId + "/journal-entries", body, actorAssertion, key);
    }

    /** POST .../journal-entries/{entry}/submit-for-approval — the MAKER submits (DELEGATED). */
    public JsonNode submitJe(UUID tenantId, UUID entryId, String actorAssertion) {
        return call(HttpMethod.POST, "/v1/tenants/" + tenantId + "/journal-entries/" + entryId
            + "/submit-for-approval", Map.of(), actorAssertion, "inv-je-submit-" + entryId);
    }

    /** POST .../journal-entries/{entry}/approve — the CHECKER approves and the JE posts (DELEGATED). */
    public JsonNode approveJe(UUID tenantId, UUID entryId, String actorAssertion) {
        return call(HttpMethod.POST, "/v1/tenants/" + tenantId + "/journal-entries/" + entryId + "/approve",
            Map.of(), actorAssertion, "inv-je-approve-" + entryId);
    }

    /** POST /v1/tenants/{id}/journal/intents — single-actor immediate posting (DELEGATED). */
    public JsonNode postIntent(UUID tenantId, Map<String, Object> body, String actorAssertion, String key) {
        return call(HttpMethod.POST, "/v1/tenants/" + tenantId + "/journal/intents", body, actorAssertion, key);
    }

    /** GET .../journal-entries/{entry} — saga recovery reads the entry's current status (DELEGATED). */
    public JsonNode getJeEntry(UUID tenantId, UUID entryId, String actorAssertion) {
        return call(HttpMethod.GET, "/v1/tenants/" + tenantId + "/journal-entries/" + entryId, null, actorAssertion, null);
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

    /** Installations of this plugin awaiting readiness (MACHINE). */
    public List<Map<String, Object>> provisioning(String pluginCode) {
        JsonNode n = call(HttpMethod.GET, "/v1/plugins/" + pluginCode + "/installations/provisioning", null, null, null);
        return MAPPER.convertValue(n, MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    /** Report one readiness gate for an installation (MACHINE). */
    public void reportReady(UUID installationId, String gate) {
        call(HttpMethod.POST, "/v1/plugins/installations/" + installationId + "/ready",
            Map.of("gate", gate, "status", "ACTIVE"), null, "inv-ready-" + installationId + "-" + gate);
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
