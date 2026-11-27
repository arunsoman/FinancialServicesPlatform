package com.fsp.core.customer;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import com.fsp.core.identity.IdpInternalClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Plugin-facing person surface for KYC onboarding (V36). The KYC plugin owns the customer
 * case and widget; it reaches these through the gateway on behalf of the person (DELEGATED),
 * and records verdicts as its machine client. The IdP hop happens only after the person's
 * transaction committed — never inside it (AGENTS §2d).
 */
@Slf4j
@RestController
public class CustomerController {

    private final CustomerOnboardingService service;
    private final IdpInternalClient idp;

    public CustomerController(CustomerOnboardingService service, IdpInternalClient idp) {
        this.service = service;
        this.idp = idp;
    }

    @FspOperation(id = "customer.create", component = "customer", permission = "org.onboard_child",
        risk = FspOperation.OperationRisk.WRITE,
        title = "Onboard a customer (KYC saga, delegated) — activation goes to the primary contact",
        actors = {Actor.ActorType.DELEGATED, Actor.ActorType.PERSON})
    @PostMapping("/v1/tenants/{tenantId}/customers")
    public ResponseEntity<CustomerDtos.CustomerView> create(@PathVariable("tenantId") UUID tenantId,
                                                            @RequestBody CustomerDtos.CreateCustomer body,
                                                            HttpServletRequest request) {
        CustomerDtos.CustomerView created;
        try {
            created = service.create(tenantId, body, actor(request));
        } catch (DataIntegrityViolationException e) {
            // lost the race on the partial unique contact index (V36)
            log.warn("customer_create_refused reason=contact_taken_race tenant={}", tenantId);
            throw ApiException.conflict("CUSTOMER_CONTACT_TAKEN", "Email or phone is already registered.");
        }
        sendActivation(created.personId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @FspOperation(id = "customer.get", component = "customer", permission = "kyc.review",
        title = "One onboarded customer: status and whether the contact is verified",
        actors = {Actor.ActorType.DELEGATED, Actor.ActorType.PERSON})
    @GetMapping("/v1/tenants/{tenantId}/customers/{personId}")
    public CustomerDtos.CustomerView get(@PathVariable("tenantId") UUID tenantId,
                                         @PathVariable("personId") UUID personId) {
        return service.get(tenantId, personId);
    }

    @FspOperation(id = "person.activation.resend", component = "customer", permission = "org.onboard_child",
        risk = FspOperation.OperationRisk.WRITE,
        title = "Re-send a person's activation (link or code) to their primary contact",
        actors = {Actor.ActorType.DELEGATED, Actor.ActorType.PERSON})
    @PostMapping("/v1/tenants/{tenantId}/persons/{personId}/activation")
    public CustomerDtos.ActivationSent resend(@PathVariable("tenantId") UUID tenantId,
                                              @PathVariable("personId") UUID personId,
                                              HttpServletRequest request) {
        var p = service.requireAwaitingActivation(tenantId, personId);
        idp.triggerActivation(p.id); // supersedes any earlier link/code
        service.recordActivationResent(tenantId, p, actor(request));
        log.info("person_activation_resent personId={} tenant={} by={}", p.id, tenantId, actor(request).personId());
        return new CustomerDtos.ActivationSent(p.id, p.primaryContact);
    }

    @FspOperation(id = "customer.verification.record", component = "customer", permission = "plugin.manage",
        risk = FspOperation.OperationRisk.WRITE,
        title = "Record a customer KYC verdict (machine-only); VERIFIED activates a contact-verified customer",
        actors = {Actor.ActorType.MACHINE})
    @PostMapping("/v1/tenants/{tenantId}/customers/{personId}/verification")
    public CustomerDtos.VerificationReceipt verification(@PathVariable("tenantId") UUID tenantId,
                                                         @PathVariable("personId") UUID personId,
                                                         @RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        UUID approvalId;
        try {
            approvalId = UUID.fromString(String.valueOf(body.get("approvalId")));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("VERIFICATION_INVALID", "approvalId must be a UUID");
        }
        return service.recordVerdict(tenantId, personId, str(body.get("verdict")), approvalId,
            str(body.get("payloadHash")), actor(request));
    }

    private void sendActivation(UUID personId) {
        try {
            idp.triggerActivation(personId);
        } catch (RuntimeException e) {
            // the customer exists; the maker can re-send from the case
            log.warn("customer_activation_send_failed personId={} reason={}", personId, e.getClass().getSimpleName());
        }
    }

    private static Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
