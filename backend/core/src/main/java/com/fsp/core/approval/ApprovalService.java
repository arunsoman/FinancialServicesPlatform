package com.fsp.core.approval;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.HashUtil;
import com.fsp.core.common.KeyLocks;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generic four-eyes engine: approval rows are opened by domain flows (with-kyc, JE submit)
 * and resolved by the checker from the queue or the owning domain endpoint. Resolution is
 * idempotent ONLY when identical (same checker, same decision); anything else conflicts.
 * Every resolution dispatches to the owning domain via {@link ApprovalHandler} by OpType.
 */
@Slf4j
@Service
public class ApprovalService {

    private final ApprovalRequestRepository repository;
    private final PermissionService permissions;
    private final KeyLocks keyLocks;
    private final com.fsp.core.audit.AuditService audit;
    private final OutboxService outbox;
    private final com.fsp.core.identity.PersonRepository persons;
    private final com.fsp.core.plugin.registry.PluginTypeRepository pluginTypes;
    private final Map<String, ApprovalHandler> handlers = new HashMap<>();
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final com.fsp.core.notify.NotificationService notifications;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    public ApprovalService(ApprovalRequestRepository repository, PermissionService permissions,
                           KeyLocks keyLocks, com.fsp.core.audit.AuditService audit,
                           OutboxService outbox, List<ApprovalHandler> handlerList,
                           com.fsp.core.identity.PersonRepository persons,
                           com.fsp.core.plugin.registry.PluginTypeRepository pluginTypes,
                           org.springframework.transaction.PlatformTransactionManager txManager,
                           com.fsp.core.notify.NotificationService notifications) {
        this.repository = repository;
        this.permissions = permissions;
        this.keyLocks = keyLocks;
        this.audit = audit;
        this.outbox = outbox;
        this.persons = persons;
        this.pluginTypes = pluginTypes;
        this.notifications = notifications;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        for (ApprovalHandler h : handlerList) handlers.put(h.operationType(), h);
    }

    // ---------- open (maker side) ----------

    @Transactional
    public ApprovalRequest open(UUID tenantId, String operationType, UUID targetResourceId,
                                Actor maker, String payloadDescription, String payload) {
        return open(tenantId, operationType, targetResourceId, maker, maker == null ? null : maker.personId(), payloadDescription, payload, null);
    }

    /** Look up an approval request by id (used by domain handlers). */
    public ApprovalRequest findById(UUID approvalId) {
        return repository.findById(approvalId)
            .orElseThrow(() -> ApiException.notFound("APPROVAL_NOT_FOUND",
                "Approval request %s not found".formatted(approvalId)));
    }

    /** Overload used when the maker is NOT the caller (e.g. with-kyc: submittedBy = PSP admin). */
    @Transactional
    public ApprovalRequest open(UUID tenantId, String operationType, UUID targetResourceId,
                                Actor contextActor, UUID makerId, String payloadDescription, String payload) {
        return open(tenantId, operationType, targetResourceId, contextActor, makerId, payloadDescription, payload, null);
    }

    /** Full overload with client attribution for plugin-mediated (DELEGATED) approvals. */
    @Transactional
    public ApprovalRequest open(UUID tenantId, String operationType, UUID targetResourceId,
                                Actor contextActor, UUID makerId, String payloadDescription, String payload,
                                String clientId) {
        String opType = canonicalOpType(operationType);

        // Machine maker refusal (plugin-platform-plan §7.1): a machine actor can never be a maker.
        // DELEGATED carries a personId and is allowed — the person is the maker.
        if (contextActor != null && contextActor.type() == Actor.ActorType.MACHINE) {
            log.warn("machine_maker_refused opType={} clientId={}", opType, contextActor.clientId());
            throw ApiException.forbidden("MACHINE_MAKER",
                "A plugin machine actor cannot be a maker; four-eyes is person-anchored at person_id");
        }

        // One pending request per (type, target) — ux_approvals_pending_target enforces it; say so
        // plainly instead of letting the unique violation surface as a 500 "Internal error".
        if (targetResourceId != null && repository.existsByOperationTypeAndTargetResourceIdAndStatus(
                opType, targetResourceId, ApprovalRequest.Status.PENDING_CHECKER)) {
            log.info("approval_open_duplicate type={} target={} maker={}", opType, targetResourceId, makerId);
            throw ApiException.conflict("APPROVAL_ALREADY_PENDING",
                "A request like this is already waiting for a checker — approve or reject that one first");
        }

        String payloadHash = HashUtil.sha256Hex(opType + "|" + targetResourceId + "|" + payloadDescription);
        ApprovalRequest row = new ApprovalRequest();
        row.id = UUID.randomUUID();
        row.tenantId = tenantId;
        row.operationType = opType;
        row.targetResourceId = targetResourceId;
        row.payloadHash = payloadHash;
        row.payload = payload;
        row.makerId = makerId;
        row.clientId = clientId;
        repository.save(row);
        log.info("approval_opened approvalId={} type={} target={} maker={} tenant={}",
            row.id, opType, targetResourceId, makerId, tenantId);
        outbox.record("ApprovalRequest", row.id, "Approval.Requested",
            "{\"approvalId\":\"%s\",\"type\":\"%s\",\"target\":\"%s\"}".formatted(row.id, opType, targetResourceId));
        notifyCheckers(row, makerId);
        return row;
    }

    /** F-12: point the tenant's checker plane at this open approval (same transaction, deduped inside). */
    private void notifyCheckers(ApprovalRequest row, UUID makerId) {
        try {
            String perm = checkerPermissionOf(row);
            if (perm != null) {
                notifications.notifyCheckers(row.tenantId, row.operationType, perm,
                    row.id, makerId);
            }
        } catch (com.fsp.core.common.ApiException denied) {
            log.debug("approval_notify_skipped approvalId={} reason={}", row.id, denied.getMessage());
        }
    }

    // ---------- resolve (checker side) ----------

    /**
     * Queue/endpoint resolution by approval id. Four-eyes + state precondition here,
     * then dispatch to the owning domain handler. Serializes per approval id (§2d).
     * The required permission depends on the operation domain.
     */
    public void resolve(UUID approvalId, boolean approved, Actor checker, String reason) {
        ApprovalRequest row = repository.findById(approvalId)
            .orElseThrow(() -> ApiException.notFound("APPROVAL_NOT_FOUND", "Approval request not found"));
        requirePluginOwnsApproval(row, checker);
        requirePluginDecidesItsOwn(row, checker);
        requirePermissionFor(row, checker);
        // DB-only domain effects (USER_*, ROLE_*) run in ONE transaction with the approval row: a
        // handler that refuses (stale role, user state, …) rolls the resolution back instead of
        // leaving an APPROVED row whose change never happened. Handlers that dispatch remotely
        // (JE_POSTING → TigerBeetle) keep their own boundaries (ApprovalHandler#atomicWithResolution).
        // (resolveLocked is self-invoked, so its @Transactional is NOT proxied — hence the template.)
        ApprovalHandler handler = handlers.get(row.operationType);
        if (handler != null && handler.atomicWithResolution()) {
            keyLocks.withLock("approval", approvalId, () -> {
                tx.executeWithoutResult(status -> resolveLocked(approvalId, approved, checker, reason));
            });
        } else {
            keyLocks.withLock("approval", approvalId, () -> resolveLocked(approvalId, approved, checker, reason));
        }
    }

    /**
     * A PLUGIN:* row is the evidence of a decision the PLUGIN owns (e.g. a KYC case). Resolving it
     * straight in core would leave the plugin's case undecided and later conflict, so persons
     * decide such rows only through the plugin (which resolves as a DELEGATED actor).
     */
    private void requirePluginDecidesItsOwn(ApprovalRequest row, Actor checker) {
        if (row.operationType.startsWith("PLUGIN:") && checker.type() != Actor.ActorType.DELEGATED) {
            log.warn("plugin_owned_approval_direct_resolve approvalId={} type={} personId={}",
                row.id, row.operationType, checker.personId());
            throw ApiException.conflict("APPROVAL_PLUGIN_OWNED",
                "This request is decided in the %s plugin's own screen".formatted(row.operationType.split(":")[1]));
        }
    }

    /** A plugin acting for a person may resolve ONLY approvals its own client opened (plan §7.1). */
    private void requirePluginOwnsApproval(ApprovalRequest row, Actor checker) {
        String client = checker.clientId();
        if (checker.type() != Actor.ActorType.DELEGATED || client == null || !client.startsWith("plugin-")) return;
        String prefix = "PLUGIN:" + client.substring("plugin-".length()) + ":";
        if (!row.operationType.startsWith(prefix) || !client.equals(row.clientId)) {
            log.warn("plugin_resolve_foreign_approval approvalId={} type={} client={}", row.id, row.operationType, client);
            throw ApiException.forbidden("APPROVAL_CLIENT_MISMATCH",
                "Client '%s' may only resolve approvals it opened".formatted(client));
        }
    }

    /** Permission required to resolve an approval, by operation domain. */
    private void requirePermissionFor(ApprovalRequest row, Actor checker) {
        if (row.operationType.startsWith("PLUGIN:")) {
            String perm = pluginCheckerPermission(row.operationType);
            permissions.require(checker, perm, row.tenantId);
            // Plugin-mediated resolution: the plugin's client must also hold the grant (ON_BEHALF)
            if (checker.type() == Actor.ActorType.DELEGATED) {
                permissions.requireClient(checker.clientId(), perm, row.tenantId);
            }
            return;
        }
        String perm = switch (row.operationType) {
            case ApprovalRequest.OpType.KYC_TENANT_ACTIVATION -> "kyc.approve";
            case ApprovalRequest.OpType.JE_POSTING -> "je.approve";
            case ApprovalRequest.OpType.UI_LAYOUT_PUBLISH -> "ui.layout.manage";
            case ApprovalRequest.OpType.USER_CREATE,
                 ApprovalRequest.OpType.USER_UPDATE,
                 ApprovalRequest.OpType.USER_DEACTIVATE,
                 ApprovalRequest.OpType.USER_ACTIVATE,
                 ApprovalRequest.OpType.USER_REMOVE,
                 ApprovalRequest.OpType.USER_PASSWORD_RESET -> "user.manage";
            case ApprovalRequest.OpType.PLUGIN_REGISTER,
                 ApprovalRequest.OpType.PLUGIN_INSTALL -> "plugin.manage";
            case ApprovalRequest.OpType.ROLE_CREATE,
                 ApprovalRequest.OpType.ROLE_UPDATE,
                 ApprovalRequest.OpType.ROLE_DELETE,
                 ApprovalRequest.OpType.BRANCH_CREATE,
                 ApprovalRequest.OpType.BRANCH_UPDATE,
                 ApprovalRequest.OpType.BRANCH_DELETE -> "role.manage";
            case ApprovalRequest.OpType.BANK_ACCOUNT_CREATE -> "bank.account.approve";
            case ApprovalRequest.OpType.INVOICE_VOID -> "invoice.void.approve";
            default -> "user.manage";
        };
        permissions.require(checker, perm, row.tenantId);
    }

    /** The permission a checker needs for this row (core types; plugin rows read the manifest). */
    private String checkerPermissionOf(ApprovalRequest row) {
        if (row.operationType.startsWith("PLUGIN:")) {
            try {
                return pluginCheckerPermission(row.operationType);
            } catch (ApiException e) {
                return null;
            }
        }
        return switch (row.operationType) {
            case ApprovalRequest.OpType.KYC_TENANT_ACTIVATION -> "kyc.approve";
            case ApprovalRequest.OpType.JE_POSTING -> "je.approve";
            case ApprovalRequest.OpType.UI_LAYOUT_PUBLISH -> "ui.layout.manage";
            case ApprovalRequest.OpType.PLUGIN_REGISTER, ApprovalRequest.OpType.PLUGIN_INSTALL -> "plugin.manage";
            case ApprovalRequest.OpType.ROLE_CREATE, ApprovalRequest.OpType.ROLE_UPDATE,
                 ApprovalRequest.OpType.ROLE_DELETE,
                 ApprovalRequest.OpType.BRANCH_CREATE, ApprovalRequest.OpType.BRANCH_UPDATE,
                 ApprovalRequest.OpType.BRANCH_DELETE -> "role.manage";
            case ApprovalRequest.OpType.BANK_ACCOUNT_CREATE -> "bank.account.approve";
            case ApprovalRequest.OpType.INVOICE_VOID -> "invoice.void.approve";
            default -> "user.manage";
        };
    }

    /** checkerPermission declared by the REGISTERED plugin manifest for "PLUGIN:<code>:<action>". */
    private String pluginCheckerPermission(String opType) {
        String[] parts = opType.split(":", 3);
        if (parts.length == 3) {
            for (var t : pluginTypes.findByCodeAndStatus(parts[1], "REGISTERED")) {
                try {
                    for (var a : MAPPER.readTree(t.manifest).path("approvals")) {
                        String perm = a.path("checkerPermission").asText();
                        if (parts[2].equals(a.path("action").asText()) && !perm.isBlank()) return perm;
                    }
                } catch (Exception e) {
                    log.warn("plugin_manifest_unreadable code={}", parts[1]);
                }
            }
        }
        log.warn("plugin_checker_permission_undeclared opType={}", opType);
        throw ApiException.forbidden("APPROVAL_NOT_DECLARED",
            "No checker permission is declared for %s".formatted(opType));
    }

    /** Checker flag (docs/checker-flag.md): only persons flagged is_checker may resolve approvals. */
    private void requireCheckerFlag(Actor checker) {
        boolean flagged = checker.personId() != null
            && persons.findById(checker.personId()).map(p -> p.isChecker).orElse(false);
        if (!flagged) {
            log.warn("checker_flag_missing personId={}", checker.personId());
            throw ApiException.forbidden("CHECKER_NOT_ENABLED",
                "You are not enabled as a checker; ask an administrator to set the checker flag on your user");
        }
    }

    @Transactional
    protected void resolveLocked(UUID approvalId, boolean approved, Actor checker, String reason) {
        ApprovalRequest row = repository.findById(approvalId)
            .orElseThrow(() -> ApiException.notFound("APPROVAL_NOT_FOUND", "Approval request not found"));
        resolveRow(row, approved, checker, reason);
        ApprovalHandler handler = handlers.get(row.operationType);
        if (handler != null) {
            handler.apply(row, approved, checker, reason);
        } else {
            log.debug("approval_evidence_only approvalId={} type={}", row.id, row.operationType);
        }
    }

    /**
     * Evidence resolution from a domain flow (KYC decide, JE approve). Idempotent: re-resolving
     * the same (checker, decision) is a no-op; anything else throws. Returns the row.
     */
    @Transactional
    public ApprovalRequest resolveByTarget(UUID tenantId, String operationType, UUID targetResourceId,
                                           boolean approved, Actor checker, String reason) {
        String opType = canonicalOpType(operationType);
        ApprovalRequest row = repository.findByOperationTypeAndTargetResourceId(opType, targetResourceId)
            .orElse(null);
        if (row == null) {
            log.warn("approval_missing_for_domain tenantId={} type={} target={} — creating evidence row",
                tenantId, opType, targetResourceId);
            return open(tenantId, opType, targetResourceId, checker, checker.personId(), "late-opened evidence", null);
        }
        resolveRow(row, approved, checker, reason);
        return row;
    }

    /** Core transition (caller supplies tx). Same checker + same decision → silent no-op. */
    private void resolveRow(ApprovalRequest row, boolean approved, Actor checker, String reason) {
        if (row.status.equals(ApprovalRequest.Status.APPROVED)
            && approved && checker.personId().equals(row.checkerId)) {
            return;
        }
        if (row.status.equals(ApprovalRequest.Status.REJECTED)
            && !approved && checker.personId().equals(row.checkerId)) {
            return;
        }
        if (!ApprovalRequest.Status.PENDING_CHECKER.equals(row.status)) {
            log.warn("approval_transition_conflict approvalId={} status={} attempted={} checker={}",
                row.id, row.status, approved ? "APPROVE" : "REJECT", checker.personId());
            throw ApiException.conflict("APPROVAL_STATE",
                "Approval already decided (%s)".formatted(row.status));
        }
        permissions.requireDistinctPerson(checker, row.makerId);
        requireCheckerFlag(checker);

        row.checkerId = checker.personId();
        row.status = approved ? ApprovalRequest.Status.APPROVED : ApprovalRequest.Status.REJECTED;
        row.reason = reason;
        row.decidedAt = java.time.Instant.now();
        repository.save(row);

        audit.record(row.makerId, row.checkerId, row.tenantId, null, "APPROVAL_RESOLVED", "APPROVAL_REQUEST",
            row.id.toString(), audit.hashState("status=PENDING_CHECKER"),
            audit.hashState("status=" + row.status + ";type=" + row.operationType), false);
        outbox.record("ApprovalRequest", row.id, "Approval.Resolved",
            "{\"approvalId\":\"%s\",\"type\":\"%s\",\"status\":\"%s\"}".formatted(row.id, row.operationType, row.status));
        // V38: nobody needs to act any more — every checker's pointer leaves their pending inbox
        notifications.resolveApproval(row.id);
        log.info("approval_resolved approvalId={} type={} status={} checker={}",
            row.id, row.operationType, row.status, row.checkerId);
    }

    // ---------- queue ----------

    /** Every permission that lets someone decide SOME approval type — any one opens the queue. */
    static final List<String> CHECKER_PERMISSIONS = List.of(
        "kyc.approve", "kyc.reject", "kyc.review", "je.approve", "ui.layout.manage",
        "user.manage", "role.manage", "plugin.manage", "invoice.void.approve");

    public List<ApprovalDtos.QueueView> queue(UUID tenantId, List<String> statuses,
                                              String operationType, Actor actor) {
        permissions.requireAny(actor, CHECKER_PERMISSIONS, tenantId);
        List<String> statusFilter = statuses == null || statuses.isEmpty() ? null : statuses;
        // Enum-equality filters are pushed down to the database (guide §4A search push-down) —
        // a pending UI_LAYOUT_PUBLISH row beyond an arbitrary newest-first page must still be
        // decidable, so filtering happens before any caller-side limit.
        List<ApprovalRequest> rows;
        if (operationType != null && !operationType.isBlank()) {
            rows = statusFilter == null
                ? repository.findByTenantIdAndOperationTypeOrderByRequestedAtDesc(tenantId, operationType)
                : repository.findByTenantIdAndOperationTypeAndStatusInOrderByRequestedAtDesc(
                    tenantId, operationType, statusFilter);
        } else if (statusFilter == null) {
            rows = repository.findByTenantIdOrderByRequestedAtDesc(tenantId);
        } else if (statusFilter.size() == 1) {
            rows = repository.findByTenantIdAndStatusOrderByRequestedAtDesc(tenantId, statusFilter.getFirst());
        } else {
            rows = repository.findByTenantIdAndStatusInOrderByRequestedAtDesc(tenantId, statusFilter);
        }
        java.util.Set<String> held = permissions.effectiveKeys(actor, tenantId);
        boolean flagged = actor.personId() != null
            && persons.findById(actor.personId()).map(p -> p.isChecker).orElse(false);
        Map<UUID, String> names = new HashMap<>();
        return rows.stream().map(r -> view(r, actor, held, flagged, names)).toList();
    }

    /**
     * One request for the caller (the notification bell opens the approval it points at). Same
     * gate as the queue, evaluated in the ROW's tenant — a person without a checker permission
     * there gets 403, and a tenant they cannot see is indistinguishable from a missing row.
     */
    public ApprovalDtos.QueueView viewById(UUID approvalId, Actor actor) {
        ApprovalRequest row = repository.findById(approvalId)
            .orElseThrow(() -> ApiException.notFound("APPROVAL_NOT_FOUND", "Approval request not found"));
        permissions.requireAny(actor, CHECKER_PERMISSIONS, row.tenantId);
        return view(row, actor);
    }

    /** Single-row view for the caller (used by the open endpoint). */
    public ApprovalDtos.QueueView view(ApprovalRequest row, Actor actor) {
        java.util.Set<String> held = permissions.effectiveKeys(actor, row.tenantId);
        boolean flagged = actor.personId() != null
            && persons.findById(actor.personId()).map(p -> p.isChecker).orElse(false);
        return view(row, actor, held, flagged, new HashMap<>());
    }

    private ApprovalDtos.QueueView view(ApprovalRequest r, Actor actor, java.util.Set<String> held, boolean flagged,
                                        Map<UUID, String> names) {
        String perm = checkerPermissionOf(r);
        String blocked;
        if (!ApprovalRequest.Status.PENDING_CHECKER.equals(r.status)) blocked = "DECIDED";
        else if (r.operationType.startsWith("PLUGIN:")) blocked = "PLUGIN_OWNED";
        else if (actor.personId() != null && actor.personId().equals(r.makerId)) blocked = "OWN_REQUEST";
        else if (perm == null || !held.contains(perm)) blocked = "NO_PERMISSION";
        else if (!flagged) blocked = "NOT_CHECKER";
        else blocked = null;
        // payload only for core-owned rows the checker needs to JUDGE: person/role changes and
        // the UI layout/menu snapshot (what the checker is approving); plugin rows hold a hash, never data
        String payload = r.operationType.startsWith("USER_") || r.operationType.startsWith("ROLE_")
            || r.operationType.startsWith("BRANCH_")
            || ApprovalRequest.OpType.UI_LAYOUT_PUBLISH.equals(r.operationType) ? r.payload : null;
        return new ApprovalDtos.QueueView(r.id, r.tenantId, r.operationType, r.targetResourceId, r.status,
            r.makerId, nameOf(r.makerId, names), r.checkerId, nameOf(r.checkerId, names), r.reason,
            r.requestedAt, r.decidedAt, payload, blocked == null, blocked, perm);
    }

    private String nameOf(UUID personId, Map<UUID, String> cache) {
        if (personId == null) return null;
        return cache.computeIfAbsent(personId, id -> persons.findById(id).map(p -> p.username).orElse(null));
    }

    private static String canonicalOpType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("APPROVAL_TYPE", "operationType required");
        }
        // PLUGIN: prefix passes through verbatim — no enumeration of plugin types in OpType (plugin-platform-plan §7.1, D9)
        if (raw.startsWith("PLUGIN:")) return raw;
        return switch (raw) {
            case ApprovalRequest.OpType.KYC_TENANT_ACTIVATION ->
                ApprovalRequest.OpType.KYC_TENANT_ACTIVATION;
            case ApprovalRequest.OpType.JE_POSTING -> ApprovalRequest.OpType.JE_POSTING;
            case ApprovalRequest.OpType.USER_CREATE -> ApprovalRequest.OpType.USER_CREATE;
            case ApprovalRequest.OpType.USER_UPDATE -> ApprovalRequest.OpType.USER_UPDATE;
            case ApprovalRequest.OpType.USER_DEACTIVATE -> ApprovalRequest.OpType.USER_DEACTIVATE;
            case ApprovalRequest.OpType.USER_ACTIVATE -> ApprovalRequest.OpType.USER_ACTIVATE;
            case ApprovalRequest.OpType.USER_REMOVE -> ApprovalRequest.OpType.USER_REMOVE;
            case ApprovalRequest.OpType.USER_PASSWORD_RESET -> ApprovalRequest.OpType.USER_PASSWORD_RESET;
            case ApprovalRequest.OpType.UI_LAYOUT_PUBLISH -> ApprovalRequest.OpType.UI_LAYOUT_PUBLISH;
            case ApprovalRequest.OpType.PLUGIN_REGISTER -> ApprovalRequest.OpType.PLUGIN_REGISTER;
            case ApprovalRequest.OpType.PLUGIN_INSTALL -> ApprovalRequest.OpType.PLUGIN_INSTALL;
            case ApprovalRequest.OpType.ROLE_CREATE -> ApprovalRequest.OpType.ROLE_CREATE;
            case ApprovalRequest.OpType.ROLE_UPDATE -> ApprovalRequest.OpType.ROLE_UPDATE;
            case ApprovalRequest.OpType.ROLE_DELETE -> ApprovalRequest.OpType.ROLE_DELETE;
            case ApprovalRequest.OpType.BRANCH_CREATE -> ApprovalRequest.OpType.BRANCH_CREATE;
            case ApprovalRequest.OpType.BRANCH_UPDATE -> ApprovalRequest.OpType.BRANCH_UPDATE;
            case ApprovalRequest.OpType.BRANCH_DELETE -> ApprovalRequest.OpType.BRANCH_DELETE;
            case ApprovalRequest.OpType.BANK_ACCOUNT_CREATE -> ApprovalRequest.OpType.BANK_ACCOUNT_CREATE;
            case ApprovalRequest.OpType.INVOICE_VOID -> ApprovalRequest.OpType.INVOICE_VOID;
            default -> ApprovalRequest.OpType.GENERIC;
        };
    }
}
