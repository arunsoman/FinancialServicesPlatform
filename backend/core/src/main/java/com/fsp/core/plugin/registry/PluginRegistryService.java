package com.fsp.core.plugin.registry;

import com.fsp.core.approval.ApprovalService;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Plugin type registration (vision §6.2): a plugin type + version is registered once,
 * platform-wide, under four-eyes. The manifest is hashed + stored verbatim.
 * On approval, the type becomes REGISTERED and its operations enter the signed catalog.
 */
@Slf4j
@Service
public class PluginRegistryService {

    private final PluginTypeRepository types;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    public PluginRegistryService(PluginTypeRepository types, ApprovalService approvals,
                                 AuditService audit, OutboxService outbox,
                                 org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.types = types;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    /** Submit a plugin type for registration (maker = caller). Four-eyes: the type is stored
     *  PENDING_APPROVAL and a PLUGIN_REGISTER approval is opened. */
    @Transactional
    public PluginType register(String code, String version, String baseUrl, String manifest,
                               String manifestHash, Actor actor) {
        if (types.existsByCodeAndVersion(code, version)) {
            throw ApiException.conflict("PLUGIN_TYPE_EXISTS",
                "Plugin type %s@%s is already registered".formatted(code, version));
        }
        PluginType type = new PluginType(UUID.randomUUID(), code, version, baseUrl, manifest, manifestHash,
            "PENDING_APPROVAL", actor.personId());
        types.save(type);

        // Open four-eyes approval; the maker is the submitting person
        ApprovalRequest row = approvals.open(
            actor.activeTenantId(), ApprovalRequest.OpType.PLUGIN_REGISTER, type.id,
            actor, actor.personId(),
            "plugin.register %s@%s".formatted(code, version),
            manifestHash);

        audit.record(actor.personId(), null, actor.activeTenantId(), null,
            "PLUGIN_REGISTER_SUBMITTED", "PLUGIN_TYPE", type.id.toString(),
            null, audit.hashState("code=" + code + ";version=" + version + ";status=PENDING_APPROVAL"), false);
        outbox.record("PluginType", type.id, "PluginType.Submitted",
            "{\"code\":\"%s\",\"version\":\"%s\",\"maker\":\"%s\"}".formatted(code, version, actor.personId()));

        log.info("plugin_register_submitted code={} version={} maker={} approvalId={}",
            code, version, actor.personId(), row.id);
        return type;
    }

    /** Called by the PLUGIN_REGISTER approval handler after the checker approves. */
    @Transactional
    public PluginType approve(String code, String version, Actor checker) {
        // the approved version, never "whichever pending row of this code comes first"
        PluginType type = types.findByCodeAndStatus(code, "PENDING_APPROVAL").stream()
            .filter(t -> version == null || version.equals(t.version))
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("PLUGIN_TYPE_NOT_PENDING",
                "Plugin type %s@%s is not pending approval".formatted(code, version)));
        // One REGISTERED version per code: the gateway's routes, the manifest's declared
        // approvals and its grants are read per code, so an older registration is superseded
        // (DEACTIVATED) in the same transaction. Installations are per code and keep running.
        for (PluginType older : types.findByCodeAndStatus(code, "REGISTERED")) {
            older.status = "DEACTIVATED";
            types.save(older);
            audit.record(null, checker.personId(), checker.activeTenantId(), null,
                "PLUGIN_SUPERSEDED", "PLUGIN_TYPE", older.id.toString(),
                null, audit.hashState("code=" + code + ";version=" + older.version + ";supersededBy=" + type.version), false);
            log.info("plugin_superseded code={} version={} by={}", code, older.version, type.version);
        }
        type.status = "REGISTERED";
        type.approvedBy = checker.personId();
        type.approvedAt = Instant.now();
        types.save(type);
        provisionMachineClient(type, checker);

        audit.record(null, checker.personId(), checker.activeTenantId(), null,
            "PLUGIN_REGISTERED", "PLUGIN_TYPE", type.id.toString(),
            null, audit.hashState("code=" + code + ";version=" + version + ";status=REGISTERED"), false);
        outbox.record("PluginType", type.id, "PluginType.Registered",
            "{\"code\":\"%s\",\"version\":\"%s\",\"checker\":\"%s\"}".formatted(code, version, checker.personId()));

        log.info("plugin_registered code={} version={} checker={}", code, version, checker.personId());
        return type;
    }

    /**
     * The plugin's machine identity comes ONLY from the four-eyes-approved manifest (plan §5.1):
     * client id plugin-&lt;code&gt;, its PUBLIC client-auth JWKS, and a CLOSED list of grants (each
     * must be an existing permission). Created on approval, owned by the approver's tenant.
     */
    private void provisionMachineClient(PluginType type, Actor checker) {
        com.fasterxml.jackson.databind.JsonNode m;
        try {
            m = MAPPER.readTree(type.manifest);
        } catch (Exception e) {
            throw ApiException.badRequest("PLUGIN_MANIFEST_INVALID", "manifest is not JSON");
        }
        String clientId = "plugin-" + type.code;
        com.fasterxml.jackson.databind.JsonNode jwks = m.path("client").path("jwks");
        if (!jwks.path("keys").isArray() || jwks.path("keys").isEmpty()) {
            throw ApiException.badRequest("PLUGIN_MANIFEST_INVALID", "manifest.client.jwks.keys is required");
        }
        for (com.fasterxml.jackson.databind.JsonNode k : jwks.path("keys")) {
            if (!k.path("d").isMissingNode()) {
                throw ApiException.badRequest("PLUGIN_MANIFEST_INVALID", "client jwks must hold public keys only");
            }
        }
        UUID owner = checker.activeTenantId();
        jdbc.update("INSERT INTO oauth_clients (client_id, client_type, jwks, grant_types, status, owner_tenant_id, token_ttl_s) "
                + "VALUES (?, 'MACHINE', ?::jsonb, '[\"client_credentials\"]', 'ACTIVE', ?, 300) "
                + "ON CONFLICT (client_id) DO UPDATE SET jwks = EXCLUDED.jwks, status = 'ACTIVE', updated_at = now()",
            clientId, jwks.toString(), owner);
        int granted = 0;
        for (com.fasterxml.jackson.databind.JsonNode g : m.path("client").path("grants")) {
            String key = g.path("permission").asText();
            String kind = g.path("kind").asText("MACHINE");
            Integer known = jdbc.queryForObject("SELECT count(*) FROM permissions WHERE key = ?", Integer.class, key);
            if (!java.util.Set.of("MACHINE", "ON_BEHALF").contains(kind) || known == null || known == 0) {
                throw ApiException.badRequest("PLUGIN_MANIFEST_INVALID", "unknown grant " + key + " (" + kind + ")");
            }
            granted += jdbc.update("INSERT INTO client_grants (client_id, owner_tenant_id, operation_or_permission, kind, granted_by) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", clientId, owner, key, kind, checker.personId());
        }
        audit.record(null, checker.personId(), owner, null, "PLUGIN_CLIENT_PROVISIONED", "OAUTH_CLIENT", clientId,
            null, audit.hashState("client=" + clientId + ";grants=" + granted), false);
        log.info("plugin_client_provisioned client={} owner={} grants={}", clientId, owner, granted);
    }

    public PluginType find(String code, String version) {
        return types.findByCodeAndVersion(code, version)
            .orElseThrow(() -> ApiException.notFound("PLUGIN_TYPE_NOT_FOUND",
                "Plugin type %s@%s not found".formatted(code, version)));
    }

    /** Used by the PLUGIN_REGISTER approval handler to locate the type by id. */
    public PluginType findById(UUID id) {
        return types.findById(id).orElse(null);
    }
}
