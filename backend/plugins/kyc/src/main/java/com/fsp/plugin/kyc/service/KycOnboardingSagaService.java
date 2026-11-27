package com.fsp.plugin.kyc.service;

import com.fsp.plugin.kyc.api.ApiProblemException;
import com.fsp.plugin.kyc.api.KycDtos;
import com.fsp.plugin.kyc.domain.KycCase;
import com.fsp.plugin.kyc.domain.KycDecision;
import com.fsp.plugin.kyc.domain.KycUbo;
import com.fsp.plugin.kyc.repository.KycCaseRepository;
import com.fsp.plugin.kyc.repository.KycUboRepository;
import com.fsp.plugin.kyc.service.CoreClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Onboarding saga (§12.5 of plugin-platform-plan).
 * Core part is atomic (tenant PENDING + admin + suspended books); the plugin continues
 * with KYC case, screening, approval, and verification. Deterministic idempotency keys
 * make the saga crash-safe — recovery replays the same keys.
 */
@Slf4j
@Service
public class KycOnboardingSagaService {

    private final KycCaseService caseService;
    private final KycCaseRepository caseRepository;
    private final KycUboRepository uboRepository;
    private final ScreeningService screening;
    private final CoreClient coreClient;
    private final KycCustomerService customers;

    public KycOnboardingSagaService(KycCaseService caseService, KycCaseRepository caseRepository,
                                    KycUboRepository uboRepository, ScreeningService screening, CoreClient coreClient,
                                    KycCustomerService customers) {
        this.customers = customers;
        this.caseService = caseService;
        this.caseRepository = caseRepository;
        this.uboRepository = uboRepository;
        this.screening = screening;
        this.coreClient = coreClient;
    }

    /**
     * Onboard a new organization (plan §12.5). NOT one transaction — each step commits on its
     * own, and every remote step uses a DETERMINISTIC idempotency key derived from the case id,
     * so a retry (recovery job or the person) can never create a second org or approval:
     *  1. case row TENANT_PENDING (plugin tx)
     *  2. core creates the child org under the person's org, atomically (on-behalf, kyc-onboard-&lt;case&gt;)
     *  3. screening (simulated) → checks
     *  4. core opens the four-eyes approval for the person (on-behalf, kyc-approval-&lt;case&gt;-1)
     *  5. case PENDING_REVIEW
     * A failure after step 1 leaves the case TENANT_PENDING ("Setting up") and surfaces core's
     * problem to the person; nothing in core activates without a verified verdict.
     */
    /** Core's answer for the person's org: the one sub-type it may onboard (null = none). */
    void requireChildLevel(UUID ownerTenantId, String subType, String actorAssertion) {
        Object allowed = coreClient.getTenant(ownerTenantId, actorAssertion).get("childSubType");
        requireChildLevel(allowed == null ? null : String.valueOf(allowed), subType);
    }

    static void requireChildLevel(String allowed, String subType) {
        if (allowed == null || allowed.isBlank()) {
            throw new ApiProblemException("CHILD_NOT_ALLOWED",
                "Your organization is the last level — it can't onboard organizations below it", 409);
        }
        String asked = subType == null ? "" : subType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.equals(asked)) {
            throw new ApiProblemException("CHILD_SUBTYPE_NOT_ALLOWED",
                "Below your organization you can only onboard a " + allowed.toLowerCase(java.util.Locale.ROOT)
                    + " — not a sibling or a level above", 400);
        }
    }

    public KycCase onboard(UUID ownerTenantId, UUID makerPersonId, String makerName,
                           KycDtos.CreateCaseRequest req, String actorAssertion, String idempotencyKey) {
        // 0. the ladder, BEFORE anything persists: an org onboards only its CHILD level (never a
        //    sibling or anything above). Core decides (TenantLadder) and refuses again at step 2;
        //    asking first keeps a wrong level from leaving a stalled TENANT_PENDING case behind.
        requireChildLevel(ownerTenantId, req.subType(), actorAssertion);
        KycCase c = caseService.create(ownerTenantId, makerPersonId, makerName, req, idempotencyKey);

        Map<String, Object> tenantBody = new LinkedHashMap<>();
        tenantBody.put("legalName", req.legalName());
        tenantBody.put("subType", req.subType());
        tenantBody.put("adminUsername", adminUsername(req.legalName(), c.caseNo));
        tenantBody.put("adminFullName", req.legalName() + " administrator");
        // V2: the org's administrator and checker activate by link/code on their primary contact
        tenantBody.put("admin", contact(req.admin()));
        tenantBody.put("checker", contact(req.checker()));
        Map<String, Object> tenant = coreClient.createChildTenant(ownerTenantId, tenantBody, actorAssertion,
            "kyc-onboard-" + c.id);
        UUID coreTenantId = UUID.fromString(String.valueOf(tenant.get("id")));
        c = caseRepository.findById(c.id).orElseThrow();
        c.subjectTenantId = coreTenantId;
        if (tenant.get("invitedPersonIds") instanceof List<?> invited && invited.size() == 2) {
            c.adminPersonId = UUID.fromString(String.valueOf(invited.get(0)));
            c.subjectCheckerPersonId = UUID.fromString(String.valueOf(invited.get(1)));
            c.activationSentAt = Instant.now();
        }
        c.updatedAt = Instant.now();
        c = caseRepository.save(c);
        caseService.event(c, "TENANT_CREATED", makerName, "Organization created in the platform, pending verification");
        caseService.event(c, "ACTIVATION_SENT", "KYC plugin",
            "Administrator and checker were sent their activation (link or SMS code) — they sign in after setting a password");

        // outcomes are decided by the screening provider from the application — never by the caller
        screening.runIdentity(c.id, ownerTenantId, req.ubos());
        screening.runSanctions(c.id, ownerTenantId, req.legalName(), req.ubos());
        caseService.event(c, "SCREENED", "KYC plugin", "Identity and sanctions screening");

        String payloadHash = sha256(c.caseNo + "|" + c.legalName + "|" + c.registrationNumber + "|" + coreTenantId);
        Map<String, Object> approval = coreClient.openApproval(Map.of(
            "action", "CASE_DECISION", "targetResourceId", c.id.toString(), "payloadHash", payloadHash),
            actorAssertion, "kyc-approval-" + c.id + "-1");
        c = caseRepository.findById(c.id).orElseThrow();
        c.approvalId = UUID.fromString(String.valueOf(approval.get("id")));
        c.approvalRev = 1L;
        c.status = KycCase.STATUS_PENDING_REVIEW;
        c.updatedAt = Instant.now();
        c = caseRepository.save(c);
        caseService.event(c, "SUBMITTED", makerName, "Approval opened for a second reviewer");

        log.info("kyc_onboarding_submitted caseId={} caseNo={} tenant={} approvalId={} maker={}",
            c.id, c.caseNo, coreTenantId, c.approvalId, makerPersonId);
        return c;
    }

    /** Org-born person contact (V2) — validated by core; required so nobody is born with a shared password. */
    private static Map<String, Object> contact(KycDtos.ContactRequest c) {
        if (c == null) {
            throw new ApiProblemException("KYC_VALIDATION",
                "Administrator and checker contacts are required — their activation is sent there", 400);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", c.fullName());
        m.put("email", c.email());
        m.put("phone", c.phone());
        m.put("primaryContact", c.primaryContact());
        return m;
    }

    /** Applicant admin username: slug of the legal name + case number suffix (unique, core-valid). */
    static String adminUsername(String legalName, String caseNo) {
        String slug = legalName.toLowerCase().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.+|\\.+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40).replaceAll("\\.+$", "");
        if (slug.length() < 3) slug = "org";
        return slug + "." + caseNo.substring(caseNo.lastIndexOf('-') + 1);
    }

    /**
     * Resume a DRAFT/REQUEST_INFO case (the REQUEST_INFO four-eyes loop): the maker re-submits
     * the — possibly amended — application. It is RE-SCREENED server-side from the stored UBOS
     * (screening is the provider's verdict, never the caller's claim), a NEW approval revision
     * is opened (kyc-approval-&lt;case&gt;-&lt;rev&gt;, deterministic so a retry cannot fork approvals),
     * and the case returns to PENDING_REVIEW. The prior revision stays immutable in core.
     */
    @Transactional
    public KycCase resume(UUID caseId, UUID ownerTenantId, UUID makerPersonId, String makerName,
                          String actorAssertion, String idempotencyKey) {
        KycCase c = caseRepository.findById(caseId)
            .orElseThrow(() -> new ApiProblemException("KYC_CASE_NOT_FOUND", "Case not found", 404));
        if (!c.ownerTenantId.equals(ownerTenantId)) {
            throw new ApiProblemException("KYC_ACCESS_DENIED", "Case not accessible", 403);
        }

        // state guard + idempotency record live in the case service (DRAFT/REQUEST_INFO only)
        caseService.submit(c.id, ownerTenantId, makerPersonId, null, idempotencyKey);
        c = caseRepository.findById(c.id).orElseThrow();

        List<KycDtos.UboRequest> ubos = uboRepository.findByCaseIdOrderByPersonNoAsc(c.id).stream()
            .map(KycOnboardingSagaService::toUboRequest).toList();
        screening.runIdentity(c.id, ownerTenantId, ubos);
        screening.runSanctions(c.id, ownerTenantId, c.legalName, ubos);
        caseService.event(c, "SCREENED", "KYC plugin", "Amended application re-screened");

        long rev = (c.approvalRev != null ? c.approvalRev : 0L) + 1;
        String payloadHash = sha256(c.caseNo + "|" + c.legalName + "|" + c.registrationNumber + "|" + c.subjectTenantId);
        Map<String, Object> approval = coreClient.openApproval(Map.of(
            "action", "CASE_DECISION", "targetResourceId", c.id.toString(), "payloadHash", payloadHash),
            actorAssertion, "kyc-approval-" + c.id + "-" + rev);
        c = caseRepository.findById(c.id).orElseThrow();
        c.approvalId = UUID.fromString(String.valueOf(approval.get("id")));
        c.approvalRev = rev;
        c.status = KycCase.STATUS_PENDING_REVIEW;
        c.updatedAt = Instant.now();
        c = caseRepository.save(c);
        caseService.event(c, "SUBMITTED", makerName, "Approval revision " + rev + " opened for the checker");

        log.info("kyc_onboarding_resubmitted caseId={} caseNo={} rev={} ownerTenant={} maker={}",
            c.id, c.caseNo, rev, ownerTenantId, makerPersonId);
        return c;
    }

    private static KycDtos.UboRequest toUboRequest(KycUbo u) {
        return new KycDtos.UboRequest(u.fullName, u.dob, u.nationality, u.residentialAddress,
            u.phone, u.email, u.idDocType, u.idNumber, u.idExpiry, u.ownershipPct, u.isPep);
    }

    /** Decide a case (approve/reject/request-info).
     *  The checker's decision goes through core approval, then the plugin records
     *  the verdict with core via the machine token.
     */
    @Transactional
    public KycCase decide(UUID caseId, UUID ownerTenantId, UUID checkerPersonId,
                          KycDecision decision, String actorAssertion, String machineToken,
                          Long expectedVersion) {
        KycCase c = caseRepository.findById(caseId)
            .orElseThrow(() -> new ApiProblemException("KYC_CASE_NOT_FOUND", "Case not found", 404));

        if (!c.ownerTenantId.equals(ownerTenantId)) {
            throw new ApiProblemException("KYC_ACCESS_DENIED", "Case not accessible", 403);
        }
        if (!KycCase.STATUS_PENDING_REVIEW.equals(c.status)) {
            throw new ApiProblemException("KYC_STATE", "Case is not PENDING_REVIEW", 409);
        }
        if (expectedVersion != null && c.version != expectedVersion) {
            throw new ApiProblemException("STALE_WRITE", "Case version mismatch", 409);
        }
        if (checkerPersonId == null || checkerPersonId.equals(c.makerPersonId)) {
            throw new ApiProblemException("MAKER_CANNOT_APPROVE",
                "Checker cannot be the maker (four-eyes at person_id)", 403);
        }
        if (!caseService.isChecker(actorAssertion)) {
            throw new ApiProblemException("CHECKER_NOT_ENABLED",
                "Only designated checkers may decide KYC cases", 403);
        }

        boolean customer = KycCase.KIND_CUSTOMER.equals(c.caseKind);
        if (customer && decision instanceof KycDecision.Approved) {
            // activation = approval: only once the customer set their own password (contact proven)
            customers.requireContactVerified(c, actorAssertion);
            c = caseRepository.findById(caseId).orElseThrow();
        }
        if (KycCase.KIND_CUSTOMER.equals(c.caseKind) && decision instanceof KycDecision.RequestInfo) {
            throw new ApiProblemException("KYC_STATE",
                "Customer cases are approved or rejected; correct details by rejecting and re-registering", 409);
        }

        String reason = null;
        boolean coreApproved = false;
        if (decision instanceof KycDecision.Approved approved) {
            coreApproved = true;
            c.riskRating = approved.riskRating();
            reason = "APPROVED;risk=" + approved.riskRating();
        } else if (decision instanceof KycDecision.Rejected rejected) {
            reason = "REJECTED;" + rejected.reason();
        } else if (decision instanceof KycDecision.RequestInfo info) {
            reason = "REQUEST_INFO;" + info.reason();
        }

        // Resolve core approval on-behalf (checker ≠ maker enforced by core). The key is scoped
        // to the APPROVAL id (unique per revision) so retries collapse but the REQUEST_INFO loop
        // (revision 1 resolved, then revision 2) can never reuse another revision's key.
        String resolveKey = "kyc-approval-" + c.approvalId + "-resolve-" + Instant.now().getEpochSecond();
        coreClient.resolveApproval(c.approvalId, coreApproved, reason, actorAssertion, resolveKey);

        // Update case state
        if (coreApproved) {
            c.status = KycCase.STATUS_APPROVED;
            c.checkerPersonId = checkerPersonId;
        } else if (decision instanceof KycDecision.RequestInfo) {
            c.status = KycCase.STATUS_REQUEST_INFO;
            c.checkerPersonId = checkerPersonId;
        } else {
            c.status = KycCase.STATUS_REJECTED;
            c.checkerPersonId = checkerPersonId;
        }
        c.updatedAt = Instant.now();
        caseRepository.save(c);

        // Record tenant verdict with core (machine call) — only for APPROVE/REJECT
        if (coreApproved || decision instanceof KycDecision.Rejected) {
                String verdictStr = coreApproved ? "VERIFIED" : "REJECTED";
            if (customer) {
                try {
                    coreClient.recordCustomerVerification(c.ownerTenantId, c.subjectPersonId, verdictStr,
                        c.approvalId, KycCustomerService.payloadHash(c));
                    log.info("kyc_customer_verdict_recorded caseId={} person={} verdict={}", c.id, c.subjectPersonId, verdictStr);
                    caseService.event(c, coreApproved ? "CUSTOMER_ACTIVATED" : "CUSTOMER_REJECTED", null,
                        coreApproved ? "Customer activated — they can sign in now" : "Customer rejected");
                } catch (Exception e) {
                    log.warn("kyc_customer_verdict_failed caseId={} detail={}", c.id, e.getMessage());
                }
            } else try {
                // Same plugin-attested hash the approval was opened with (core compares them)
                coreClient.recordVerification(c.subjectTenantId, verdictStr, c.approvalId,
                    sha256(c.caseNo + "|" + c.legalName + "|" + c.registrationNumber + "|" + c.subjectTenantId));
                if (coreApproved) {
                    log.info("kyc_saga_verification_verified caseId={} subjectTenant={}", c.id, c.subjectTenantId);
                } else {
                    log.info("kyc_saga_verification_rejected caseId={} subjectTenant={}", c.id, c.subjectTenantId);
                }
            } catch (Exception e) {
                log.warn("kyc_saga_verification_failed caseId={} detail={}", c.id, e.getMessage());
                // Verdict not recorded — case stays APPROVED/REJECTED but tenant not activated.
                // Recovery will retry.
            }
        }

        log.info("kyc_case_decided caseId={} decision={} checker={}", c.id, decision.getClass().getSimpleName(), checkerPersonId);
        return c;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
