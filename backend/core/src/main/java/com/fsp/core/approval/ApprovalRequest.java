package com.fsp.core.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Generic four-eyes request (V1 `approval_requests`). Evidence for every maker-checker decision. */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    public static final class Status {
        public static final String PENDING_CHECKER = "PENDING_CHECKER";
        public static final String APPROVED = "APPROVED";
        public static final String REJECTED = "REJECTED";
    }

    /** Canonical operation types; each may be resolved by an ApprovalHandler. */
    public static final class OpType {
        public static final String KYC_TENANT_ACTIVATION = "KYC_TENANT_ACTIVATION";
        public static final String JE_POSTING = "JE_POSTING";
        public static final String USER_CREATE = "USER_CREATE";
        public static final String USER_UPDATE = "USER_UPDATE";
        public static final String USER_DEACTIVATE = "USER_DEACTIVATE";
        public static final String USER_ACTIVATE = "USER_ACTIVATE";
        public static final String USER_REMOVE = "USER_REMOVE";
        /** Admin-initiated password reset (four-eyes; checker needs user.manage). */
        public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
        public static final String UI_LAYOUT_PUBLISH = "UI_LAYOUT_PUBLISH";
        /** Organization role governance (RoleAdminService) — checker needs role.manage. */
        public static final String ROLE_CREATE = "ROLE_CREATE";
        public static final String ROLE_UPDATE = "ROLE_UPDATE";
        public static final String ROLE_DELETE = "ROLE_DELETE";
        /** Branch maintenance (BranchService, V39) — checker needs role.manage. */
        public static final String BRANCH_CREATE = "BRANCH_CREATE";
        public static final String BRANCH_UPDATE = "BRANCH_UPDATE";
        public static final String BRANCH_DELETE = "BRANCH_DELETE";
        /** Settlement bank account filing (F-10); checker needs bank.account.approve. */
        public static final String BANK_ACCOUNT_CREATE = "BANK_ACCOUNT_CREATE";
        /** Voiding an ISSUED invoice (billing); checker needs invoice.void.approve. */
        public static final String INVOICE_VOID = "INVOICE_VOID";
        /** Plugin platform op types pass through via the PLUGIN: prefix (canonicalOpType). */
        public static final String PLUGIN_REGISTER = "PLUGIN_REGISTER";
        public static final String PLUGIN_INSTALL = "PLUGIN_INSTALL";
        /** Take a registered plugin out of service / back in (Grafana › Plugins); checker needs plugin.manage. */
        public static final String PLUGIN_SUSPEND = "PLUGIN_SUSPEND";
        public static final String PLUGIN_RESUME = "PLUGIN_RESUME";
        public static final String GENERIC = "GENERIC";
    }

    @Id
    public UUID id;
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;
    @Column(name = "operation_type", nullable = false)
    public String operationType;
    @Column(name = "target_resource_id")
    public UUID targetResourceId;
    @Column(name = "payload_hash", nullable = false)
    public String payloadHash;
    @Column(name = "maker_id", nullable = false)
    public UUID makerId;
    @Column(name = "checker_id")
    public UUID checkerId;
    @Column(nullable = false)
    public String status = Status.PENDING_CHECKER;
    public String reason;
    /** Maker's payload JSON (e.g. user update DTO), stored so the handler can re-apply on approve. */
    @Column(name = "payload", columnDefinition = "TEXT")
    public String payload;
    @Column(name = "requested_at", nullable = false, updatable = false)
    public Instant requestedAt = Instant.now();
    @Column(name = "decided_at")
    public Instant decidedAt;
    @Column(nullable = false)
    public long version;

    /** Attribution: which plugin client mediated this approval (null for direct person calls). */
    @Column(name = "client_id")
    public String clientId;
}
