package com.fsp.core.plugin.authz;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Tenant verification verdict endpoint (§7.3 of plugin-platform-plan).
 * Machine-only: a plugin (checker's channel) reports a KYC verdict for a tenant.
 * Core accepts ONLY if:
 *   - approval exists and is APPROVED (for VERIFIED) or REJECTED
 *   - payloadHash matches the approval's payloadHash
 *   - approval's target belongs to this tenant
 *   - maker ≠ checker (four-eyes)
 * Then core (one transaction): sets verification_status, and for VERIFIED + PENDING
 * runs activation (ACTIVE, books opened, Tenant.Activated).
 */
@Slf4j
@Service
public class TenantVerificationService {

    private final ApprovalService approvals;
    private final TenantRepository tenants;
    private final AuditService audit;
    private final OutboxService outbox;
    private final com.fsp.core.ledger.LedgerService ledger;

    public TenantVerificationService(ApprovalService approvals, TenantRepository tenants,
                                     AuditService audit, OutboxService outbox,
                                     com.fsp.core.ledger.LedgerService ledger) {
        this.approvals = approvals;
        this.tenants = tenants;
        this.audit = audit;
        this.outbox = outbox;
        this.ledger = ledger;
    }

    public enum Verdict { VERIFIED, REJECTED }

    public record VerificationReceipt(UUID tenantId, String verdict, UUID approvalId,
                                      String actionType, UUID checkerId, String auditReceipt) {}

    @Transactional
    public VerificationReceipt record(UUID tenantId, String verdictStr, UUID approvalId,
                                      String payloadHash, Actor actor) {
        // Machine-only (§7.3)
        if (actor.type() != Actor.ActorType.MACHINE) {
            throw ApiException.forbidden("MACHINE_ONLY",
                "Only a plugin machine actor can record tenant verdicts");
        }

        Verdict verdict;
        try { verdict = Verdict.valueOf(verdictStr); }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_VERDICT", "verdict must be VERIFIED or REJECTED");
        }

        Tenant tenant = tenants.findById(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));

        ApprovalRequest approval = approvals.findById(approvalId);

        // A plugin may only use approvals its own client opened (no borrowing another plugin's evidence)
        if (approval.clientId != null && !approval.clientId.equals(actor.clientId())) {
            log.warn("verification_approval_client_mismatch approvalId={} client={}", approvalId, actor.clientId());
            throw ApiException.forbidden("APPROVAL_CLIENT_MISMATCH",
                "Approval %s was not opened by this client".formatted(approvalId));
        }

        // The subject must be the approval's tenant or inside its subtree (plugin approvals are
        // opened at the onboarding tenant; the subject is the child it created)
        boolean inScope = approval.tenantId.equals(tenantId)
            || tenants.findById(approval.tenantId).map(owner -> owner.isAncestorOf(tenant)).orElse(false);
        if (!inScope) {
            log.warn("verification_tenant_out_of_scope approvalId={} approvalTenant={} subject={}",
                approvalId, approval.tenantId, tenantId);
            throw ApiException.forbidden("APPROVAL_TENANT_MISMATCH",
                "Approval %s (tenant %s) does not cover tenant %s".formatted(approvalId, approval.tenantId, tenantId));
        }

        // Approval must be decided
        if (!ApprovalRequest.Status.APPROVED.equals(approval.status)
            && !ApprovalRequest.Status.REJECTED.equals(approval.status)) {
            throw ApiException.conflict("APPROVAL_NOT_DECIDED",
                "Approval %s is %s — must be APPROVED or REJECTED".formatted(approvalId, approval.status));
        }

        // Verdict must match approval status
        if (verdict == Verdict.VERIFIED && !ApprovalRequest.Status.APPROVED.equals(approval.status)) {
            throw ApiException.conflict("VERDICT_MISMATCH",
                "VERIFIED requires approval APPROVED, but approval is %s".formatted(approval.status));
        }
        if (verdict == Verdict.REJECTED && !ApprovalRequest.Status.REJECTED.equals(approval.status)) {
            throw ApiException.conflict("VERDICT_MISMATCH",
                "REJECTED requires approval REJECTED, but approval is %s".formatted(approval.status));
        }

        // payloadHash must match
        // Plugin approvals carry the plugin-attested payload hash (stored as the approval payload)
        String expectedHash = approval.operationType.startsWith("PLUGIN:") ? approval.payload : approval.payloadHash;
        if (payloadHash == null || !payloadHash.equals(expectedHash)) {
            log.warn("verification_payload_hash_mismatch approvalId={}", approvalId);
            throw ApiException.badRequest("PAYLOAD_HASH_MISMATCH",
                "payloadHash does not match approval's recorded hash");
        }

        // maker ≠ checker (four-eyes)
        if (approval.makerId != null && approval.checkerId != null
            && approval.makerId.equals(approval.checkerId)) {
            throw ApiException.forbidden("MAKER_CANNOT_APPROVE",
                "Maker and checker cannot be the same person");
        }

        // Record verification status
        String previousVerification = tenant.verificationStatus;
        if (verdict == Verdict.VERIFIED) {
            tenant.verificationStatus = "VERIFIED";
        } else {
            tenant.verificationStatus = "REJECTED";
        }
        tenants.save(tenant);

        // For VERIFIED + PENDING tenant: activate (ACTIVE + books open + Tenant.Activated)
        String activationOutcome = "none";
        if (verdict == Verdict.VERIFIED && "PENDING".equals(tenant.status)) {
            activateTenant(tenant, approval.checkerId);
            activationOutcome = "activated";
        }

        String auditReceipt = "receipt:" + UUID.randomUUID();
        audit.record(approval.checkerId, null, tenantId, null,
            "TENANT_VERIFICATION_" + verdict, "TENANT", tenantId.toString(),
            audit.hashState("verification=" + previousVerification),
            audit.hashState("verification=" + tenant.verificationStatus + ";verdict=" + verdict
                + ";approvalId=" + approvalId + ";activation=" + activationOutcome), false);
        outbox.record("Tenant", tenantId, "Tenant.VerificationRecorded",
            "{\"tenantId\":\"%s\",\"verdict\":\"%s\",\"approvalId\":\"%s\",\"checker\":\"%s\"}"
                .formatted(tenantId, verdict, approvalId, approval.checkerId));

        log.info("tenant_verification_recorded tenantId={} verdict={} approvalId={} checker={} activation={}",
            tenantId, verdict, approvalId, approval.checkerId, activationOutcome);

        return new VerificationReceipt(tenantId, verdict.name(), approvalId,
            approval.operationType, approval.checkerId, auditReceipt);
    }

    /** Inline tenant activation (same effect as KycActivationService.activate but without
     *  the KycCase dependency — called from the verification endpoint). */
    private void activateTenant(Tenant tenant, UUID checkerId) {
        tenant.status = "ACTIVE";
        tenant.verificationStatus = "VERIFIED";
        tenant.activatedBy = checkerId;
        tenants.save(tenant);
        // The tenant was born with SUSPENDED books (TenantChildrenService); the verdict
        // opens them (docs §7.3: "the book auto-opens on verdict").
        ledger.openBooksForTenant(tenant.id);
        audit.record(null, checkerId, tenant.id, null,
            "TENANT_ACTIVATED_BY_VERIFICATION", "TENANT", tenant.id.toString(),
            null, audit.hashState("status=PENDING;verification=VERIFIED"), false);
        outbox.record("Tenant", tenant.id, "Tenant.Activated",
            "{\"verificationPath\":true}");
        log.info("tenant_activated_by_verification tenantId={} checker={}", tenant.id, checkerId);
    }
}
