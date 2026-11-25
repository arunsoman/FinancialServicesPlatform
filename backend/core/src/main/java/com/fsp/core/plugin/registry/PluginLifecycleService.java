package com.fsp.core.plugin.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.HashUtil;
import com.fsp.core.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Plugin lifecycle beyond the first four-eyes registration (docs/plugin-registration.md):
 *
 *  - {@link #reconcileRelease}: plugins SHIP AS CODE (AGENTS.md §2.1 exception). The deploy hands
 *    core the manifests of the release it just started; the four eyes were the pull request review
 *    (and, for production, the deploy approval) — recorded in {@code provenance}. Never a machine
 *    maker or checker: the row has no FSP maker, and the people behind it are named.
 *  - {@link #submitSuspend} / {@link #submitResume}: a person takes a plugin out of service (or back)
 *    under four-eyes (PLUGIN_SUSPEND / PLUGIN_RESUME, checker needs plugin.manage). A person's
 *    suspension is never lifted by a deploy — only by a PLUGIN_RESUME approval.
 *
 * Suspended = the gateway answers 503 PLUGIN_SUSPENDED, the IdP refuses the plugin's client, data
 * stays where it is (plan I10: disable without data loss, nothing hard-deleted).
 */
@Slf4j
@Service
public class PluginLifecycleService {

    /** Platform root (seed V2) — owner of release-registered machine clients. */
    static final UUID PLATFORM_ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record DesiredPlugin(String code, String version, String baseUrl, JsonNode manifest) {}

    /** action: in_sync | registered | updated | held_suspended | suspended_not_shipped;
     *  installation (easy install): installed | provisioning | active | held_* | "" (not shipped / held). */
    public record Outcome(String code, String version, String action, String detail, String installation) {
        public Outcome(String code, String version, String action, String detail) { this(code, version, action, detail, ""); }
    }

    private final PluginTypeRepository types;
    private final PluginRegistryService registry;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;
    private final com.fsp.core.plugin.installation.PluginAutoInstaller installer;

    public PluginLifecycleService(PluginTypeRepository types, PluginRegistryService registry,
                                  @org.springframework.context.annotation.Lazy ApprovalService approvals,
                                  AuditService audit, OutboxService outbox,
                                  com.fsp.core.plugin.installation.PluginAutoInstaller installer) {
        this.types = types;
        this.registry = registry;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
        this.installer = installer;
    }

    // ------------------------------------------------------------------ release (ships as code)

    @Transactional
    public List<Outcome> reconcileRelease(String release, JsonNode provenance, List<DesiredPlugin> desired,
                                          boolean suspendMissing) {
        if (release == null || !release.matches("[a-z0-9][a-z0-9._-]{2,80}")) {
            throw ApiException.badRequest("RELEASE_INVALID", "release name required ([a-z0-9._-])");
        }
        if (provenance == null || !provenance.isObject() || provenance.path("commit").asText().isBlank()) {
            throw ApiException.badRequest("PROVENANCE_REQUIRED", "provenance with the release commit is required");
        }
        String prov = provenance.toString();
        List<Outcome> out = new ArrayList<>();
        Set<String> shipped = new HashSet<>();
        for (DesiredPlugin d : desired) {
            validate(d);
            shipped.add(d.code());
            Outcome o = reconcileOne(release, prov, d);
            if (!"held_suspended".equals(o.action())) {
                // EASY INSTALL (AGENTS.md §2.1): shipping a plugin registers AND installs it — one step
                String inst = installer.ensureOnPlatformRoot(d.code(), d.version(), null, "release " + release);
                o = new Outcome(o.code(), o.version(), o.action(), o.detail(), inst);
            }
            out.add(o);
        }
        if (suspendMissing) {
            for (PluginType t : types.findByStatus("REGISTERED")) {
                if (shipped.contains(t.code)) continue;
                suspend(t, null, "RELEASE");
                audit.record(null, null, PLATFORM_ROOT, null, "PLUGIN_SUSPENDED_BY_RELEASE", "PLUGIN_TYPE",
                    t.id.toString(), null, audit.hashState("code=" + t.code + ";version=" + t.version + ";release=" + release), false);
                outbox.record("PluginType", t.id, "PluginType.Suspended", json("code", t.code, "version", t.version,
                    "by", "release", "release", release));
                log.info("plugin_suspended_not_shipped code={} version={} release={}", t.code, t.version, release);
                out.add(new Outcome(t.code, t.version, "suspended_not_shipped", "not in release " + release));
            }
        }
        return out;
    }

    private Outcome reconcileOne(String release, String prov, DesiredPlugin d) {
        List<PluginType> rows = types.findByCode(d.code());
        PluginType held = rows.stream()
            .filter(r -> "SUSPENDED".equals(r.status) && "PERSON".equals(r.suspendedReason)).findFirst().orElse(null);
        if (held != null) {
            log.info("plugin_release_held code={} version={} suspendedBy={}", d.code(), held.version, held.suspendedBy);
            return new Outcome(d.code(), d.version(), "held_suspended",
                "suspended by a person (four-eyes) — resume it in Grafana › Plugins");
        }
        String text = d.manifest().toString();
        String hash = HashUtil.sha256Hex(text);
        PluginType same = rows.stream().filter(r -> d.version().equals(r.version)).findFirst().orElse(null);
        if (same != null && "REGISTERED".equals(same.status) && sameJson(same.manifest, d.manifest())
                && d.baseUrl().equals(same.baseUrl)) {
            return new Outcome(d.code(), d.version(), "in_sync", "");
        }
        String action;
        PluginType type;
        if (same != null) {
            // same version, other content (rotated key) or not active: the release is the truth
            action = "updated";
            type = same;
            if ("PENDING_APPROVAL".equals(same.status)) {
                // the shipped version is now registered by the release — the person's request is moot
                approvals.withdrawPendingFor(ApprovalRequest.OpType.PLUGIN_REGISTER, same.id,
                    "registered by release " + release);
            }
            type.baseUrl = d.baseUrl();
            type.manifest = text;
            type.manifestHash = hash;
        } else {
            action = "registered";
            type = new PluginType(UUID.randomUUID(), d.code(), d.version(), d.baseUrl(), text, hash, "REGISTERED", null);
        }
        // one REGISTERED version per code (see PluginRegistryService.approve); a release suspension
        // of an earlier version ends here too — the plugin is shipped again
        for (PluginType other : rows) {
            if (other == same) continue;
            if ("REGISTERED".equals(other.status) || ("SUSPENDED".equals(other.status) && "RELEASE".equals(other.suspendedReason))) {
                other.status = "DEACTIVATED";
                types.save(other);
                audit.record(null, null, PLATFORM_ROOT, null, "PLUGIN_SUPERSEDED", "PLUGIN_TYPE", other.id.toString(),
                    null, audit.hashState("code=" + other.code + ";version=" + other.version + ";supersededBy=" + d.version()), false);
            }
        }
        type.status = "REGISTERED";
        type.registeredVia = "RELEASE";
        type.releaseRef = release;
        type.provenance = prov;
        type.registeredBy = null;
        type.approvedBy = null;
        type.approvedAt = Instant.now();
        type.suspendedBy = null;
        type.suspendedAt = null;
        type.suspendedReason = null;
        types.save(type);
        registry.provisionMachineClient(type, PLATFORM_ROOT, null);

        audit.record(null, null, PLATFORM_ROOT, null, "PLUGIN_REGISTERED_BY_RELEASE", "PLUGIN_TYPE", type.id.toString(),
            null, audit.hashState("code=" + d.code() + ";version=" + d.version() + ";hash=" + hash + ";release=" + release
                + ";provenance=" + prov), false);
        outbox.record("PluginType", type.id, "PluginType.Registered",
            "{\"code\":%s,\"version\":%s,\"via\":\"release\",\"release\":%s,\"provenance\":%s}".formatted(
                q(d.code()), q(d.version()), q(release), prov));
        log.info("plugin_registered_by_release code={} version={} action={} release={} hash={}",
            d.code(), d.version(), action, release, hash);
        return new Outcome(d.code(), d.version(), action, hash);
    }

    private static void validate(DesiredPlugin d) {
        if (d.code() == null || !d.code().matches("[a-z0-9]{1,32}")) {
            throw ApiException.badRequest("PLUGIN_MANIFEST_INVALID", "plugin code must be [a-z0-9]{1,32}");
        }
        if (d.version() == null || d.version().isBlank() || d.manifest() == null || !d.manifest().isObject()) {
            throw ApiException.badRequest("PLUGIN_MANIFEST_INVALID", d.code() + ": version and manifest required");
        }
        if (!d.code().equals(d.manifest().path("code").asText()) || !d.version().equals(d.manifest().path("version").asText())) {
            throw ApiException.badRequest("PLUGIN_MANIFEST_INVALID", d.code() + ": manifest code/version do not match");
        }
        if (d.baseUrl() == null || !d.baseUrl().matches("http://plugin-[a-z0-9]+:[0-9]{2,5}")) {
            throw ApiException.badRequest("PLUGIN_MANIFEST_INVALID", d.code() + ": baseUrl must be http://plugin-<code>:<port>");
        }
    }

    // ------------------------------------------------------------------ four-eyes suspend / resume

    @Transactional
    public ApprovalRequest submitSuspend(String code, String note, Actor maker) {
        PluginType t = types.findByCodeAndStatus(code, "REGISTERED").stream().findFirst()
            .orElseThrow(() -> ApiException.notFound("PLUGIN_NOT_REGISTERED", "Plugin " + code + " has no registered version"));
        ApprovalRequest row = approvals.open(maker.activeTenantId(), ApprovalRequest.OpType.PLUGIN_SUSPEND, t.id,
            maker, maker.personId(), "plugin.suspend %s@%s%s".formatted(code, t.version, note(note)), t.manifestHash);
        audit.record(maker.personId(), null, maker.activeTenantId(), null, "PLUGIN_SUSPEND_SUBMITTED", "PLUGIN_TYPE",
            t.id.toString(), null, audit.hashState("code=" + code + ";version=" + t.version), false);
        log.info("plugin_suspend_submitted code={} version={} maker={} approvalId={}", code, t.version, maker.personId(), row.id);
        return row;
    }

    @Transactional
    public ApprovalRequest submitResume(String code, String note, Actor maker) {
        PluginType t = types.findByCodeAndStatus(code, "SUSPENDED").stream()
            .max(Comparator.comparing((PluginType r) -> r.suspendedAt == null ? Instant.EPOCH : r.suspendedAt))
            .orElseThrow(() -> ApiException.notFound("PLUGIN_NOT_SUSPENDED", "Plugin " + code + " is not suspended"));
        if (!types.findByCodeAndStatus(code, "REGISTERED").isEmpty()) {
            throw ApiException.conflict("PLUGIN_ALREADY_REGISTERED", "Another version of " + code + " is registered");
        }
        ApprovalRequest row = approvals.open(maker.activeTenantId(), ApprovalRequest.OpType.PLUGIN_RESUME, t.id,
            maker, maker.personId(), "plugin.resume %s@%s%s".formatted(code, t.version, note(note)), t.manifestHash);
        audit.record(maker.personId(), null, maker.activeTenantId(), null, "PLUGIN_RESUME_SUBMITTED", "PLUGIN_TYPE",
            t.id.toString(), null, audit.hashState("code=" + code + ";version=" + t.version), false);
        log.info("plugin_resume_submitted code={} version={} maker={} approvalId={}", code, t.version, maker.personId(), row.id);
        return row;
    }

    /** PLUGIN_SUSPEND approved (handler). */
    @Transactional
    public void applySuspend(UUID typeId, Actor checker) {
        PluginType t = types.findById(typeId)
            .orElseThrow(() -> new IllegalStateException("plugin type " + typeId + " not found"));
        if (!"REGISTERED".equals(t.status)) {
            throw ApiException.conflict("PLUGIN_NOT_REGISTERED", t.code + "@" + t.version + " is " + t.status);
        }
        suspend(t, checker.personId(), "PERSON");
        audit.record(null, checker.personId(), checker.activeTenantId(), null, "PLUGIN_SUSPENDED", "PLUGIN_TYPE",
            t.id.toString(), null, audit.hashState("code=" + t.code + ";version=" + t.version + ";status=SUSPENDED"), false);
        outbox.record("PluginType", t.id, "PluginType.Suspended",
            json("code", t.code, "version", t.version, "by", "person", "checker", String.valueOf(checker.personId())));
        log.info("plugin_suspended code={} version={} checker={}", t.code, t.version, checker.personId());
    }

    /** PLUGIN_RESUME approved (handler). */
    @Transactional
    public void applyResume(UUID typeId, Actor checker) {
        PluginType t = types.findById(typeId)
            .orElseThrow(() -> new IllegalStateException("plugin type " + typeId + " not found"));
        if (!"SUSPENDED".equals(t.status)) {
            throw ApiException.conflict("PLUGIN_NOT_SUSPENDED", t.code + "@" + t.version + " is " + t.status);
        }
        if (!types.findByCodeAndStatus(t.code, "REGISTERED").isEmpty()) {
            throw ApiException.conflict("PLUGIN_ALREADY_REGISTERED", "Another version of " + t.code + " is registered");
        }
        t.status = "REGISTERED";
        t.suspendedBy = null;
        t.suspendedAt = null;
        t.suspendedReason = null;
        types.save(t);
        registry.setMachineClientStatus(t.code, "ACTIVE");
        audit.record(null, checker.personId(), checker.activeTenantId(), null, "PLUGIN_RESUMED", "PLUGIN_TYPE",
            t.id.toString(), null, audit.hashState("code=" + t.code + ";version=" + t.version + ";status=REGISTERED"), false);
        outbox.record("PluginType", t.id, "PluginType.Resumed",
            json("code", t.code, "version", t.version, "checker", String.valueOf(checker.personId())));
        log.info("plugin_resumed code={} version={} checker={}", t.code, t.version, checker.personId());
    }

    private void suspend(PluginType t, UUID by, String reason) {
        t.status = "SUSPENDED";
        t.suspendedBy = by;
        t.suspendedAt = Instant.now();
        t.suspendedReason = reason;
        types.save(t);
        registry.setMachineClientStatus(t.code, "SUSPENDED");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean sameJson(String stored, JsonNode wanted) {
        try {
            return MAPPER.readTree(stored).equals(wanted);
        } catch (Exception e) {
            return false;
        }
    }

    private static String note(String note) {
        if (note == null || note.isBlank()) return "";
        String n = note.strip();
        return " — " + (n.length() > 200 ? n.substring(0, 200) : n);
    }

    private static String q(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(String... kv) {
        var node = MAPPER.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) node.put(kv[i], kv[i + 1]);
        return node.toString();
    }
}
