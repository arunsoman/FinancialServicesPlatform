package com.fsp.plugin.kyc.api;

import com.fsp.plugin.kyc.domain.KycCase;
import com.fsp.plugin.kyc.security.ActorAssertionFilter;
import com.fsp.plugin.kyc.service.KycCaseService;
import com.fsp.plugin.kyc.service.KycDecisionService;
import com.fsp.plugin.kyc.service.KycOnboardingSagaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * KYC case API (plan §12.4), reached only through the gateway. The caller is the person in
 * the verified gateway actor assertion ({@link ActorAssertionFilter}) — never a header the
 * plugin could be handed by anyone else. Every case is scoped to the person's organization
 * (the installation tenant); core decided the permission before the request got here.
 */
@Slf4j
@RestController
@RequestMapping("/v1/plugins/kyc")
public class KycCaseController {

    private final KycCaseService caseService;
    private final KycOnboardingSagaService sagaService;
    private final KycDecisionService decisionService;
    private final com.fsp.plugin.kyc.service.KycCustomerService customerService;

    public KycCaseController(KycCaseService caseService, KycOnboardingSagaService sagaService,
                             KycDecisionService decisionService,
                             com.fsp.plugin.kyc.service.KycCustomerService customerService) {
        this.customerService = customerService;
        this.caseService = caseService;
        this.sagaService = sagaService;
        this.decisionService = decisionService;
    }

    /** kyc.case.list — GET /cases?page&size&q&status&risk&sort */
    @GetMapping("/cases")
    public KycDtos.CaseListResponse list(HttpServletRequest request,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "risk", required = false) String risk,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "sort", defaultValue = "submittedAt,desc") String sort) {
        var actor = ActorAssertionFilter.actor(request);
        return caseService.list(actor.tenantId(), page, size, q, status, risk, kind, sort);
    }

    /** kyc.case.get — GET /cases/{caseId} */
    @GetMapping("/cases/{caseId}")
    public KycDtos.CaseView get(HttpServletRequest request, @PathVariable("caseId") UUID caseId) {
        var actor = ActorAssertionFilter.actor(request);
        caseService.find(caseId, actor.tenantId()).ifPresent(c -> customerService.refresh(c, actor.assertion()));
        return caseService.get(caseId, actor.tenantId(), actor.personId(), actor.assertion());
    }

    /** kyc.customer.create — POST /customers: Customer Registration (step 1 of 2). No password:
     *  core creates the person PENDING and the IdP sends the activation to the primary contact. */
    @PostMapping("/customers")
    public ResponseEntity<KycDtos.CaseView> createCustomer(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody KycDtos.CreateCustomerRequest req) {
        var actor = ActorAssertionFilter.actor(request);
        KycCase c = customerService.onboard(actor.tenantId(), actor.personId(), actor.username(), req,
            actor.assertion(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(caseService.get(c.id, actor.tenantId(), actor.personId(), actor.assertion()));
    }

    /** kyc.case.activation — POST /cases/{caseId}/activation: re-send the activation link/code. */
    @PostMapping("/cases/{caseId}/activation")
    public KycDtos.ActivationResent resendActivation(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable("caseId") UUID caseId) {
        var actor = ActorAssertionFilter.actor(request);
        return customerService.resend(caseId, actor.tenantId(), actor.personId(), actor.username(),
            actor.assertion(), idempotencyKey);
    }

    /** kyc.case.history — GET /cases/{caseId}/history */
    @GetMapping("/cases/{caseId}/history")
    public KycDtos.CaseHistoryResponse history(HttpServletRequest request, @PathVariable("caseId") UUID caseId) {
        var actor = ActorAssertionFilter.actor(request);
        return caseService.history(caseId, actor.tenantId());
    }

    /** kyc.case.create — POST /cases: the onboarding saga (core creates the org on-behalf). */
    @PostMapping("/cases")
    public ResponseEntity<KycDtos.CaseView> create(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody KycDtos.CreateCaseRequest req) {
        var actor = ActorAssertionFilter.actor(request);
        KycCase c = sagaService.onboard(actor.tenantId(), actor.personId(), actor.username(), req,
            actor.assertion(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(caseService.get(c.id, actor.tenantId(), actor.personId(), actor.assertion()));
    }

    /** kyc.case.update — PATCH /cases/{caseId} (DRAFT / REQUEST_INFO; expectedVersion in the body) */
    @PatchMapping("/cases/{caseId}")
    public KycDtos.CaseView update(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable("caseId") UUID caseId,
            @RequestBody KycDtos.UpdateCaseRequest req) {
        var actor = ActorAssertionFilter.actor(request);
        KycCase c = caseService.update(caseId, actor.tenantId(), req, idempotencyKey, actor.personId());
        return caseService.get(c.id, actor.tenantId(), actor.personId(), actor.assertion());
    }

    /** kyc.case.submit — POST /cases/{caseId}/submit: resume the saga (DRAFT/REQUEST_INFO →
     *  re-screen + a NEW approval revision → PENDING_REVIEW). */
    @PostMapping("/cases/{caseId}/submit")
    public KycDtos.CaseView submit(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable("caseId") UUID caseId,
            @RequestBody(required = false) KycDtos.SubmitBody body) {
        var actor = ActorAssertionFilter.actor(request);
        KycCase c = sagaService.resume(caseId, actor.tenantId(), actor.personId(), actor.username(),
            actor.assertion(), idempotencyKey);
        return caseService.get(c.id, actor.tenantId(), actor.personId(), actor.assertion());
    }

    /** kyc.case.decide — POST /cases/{caseId}/decisions */
    @PostMapping("/cases/{caseId}/decisions")
    public KycDtos.CaseView decide(HttpServletRequest request,
            @PathVariable("caseId") UUID caseId,
            @RequestBody KycDtos.DecisionBody body) {
        var actor = ActorAssertionFilter.actor(request);
        KycCase c = decisionService.decide(caseId, actor.tenantId(), actor.personId(), body,
            actor.assertion(), null, body.expectedVersion());
        return caseService.get(c.id, actor.tenantId(), actor.personId(), actor.assertion());
    }
}
