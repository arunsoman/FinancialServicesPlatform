package com.fsp.core.customer;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonProvisioning;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * KYC-onboarded customers (V36). Core is the PDP for the person; the KYC plugin owns the
 * case, its fields and the widget.
 * <ol>
 *   <li>The maker (through the plugin, DELEGATED) creates the customer PENDING with no usable
 *       password; the controller then asks the IdP to send the activation to the PRIMARY contact.</li>
 *   <li>The customer opens the link / enters the code at the IdP and sets their own password —
 *       that stamps {@code contact_verified_at}. They still cannot sign in (PENDING).</li>
 *   <li>After the checker (≠ maker) approves the plugin's CASE_DECISION approval, the plugin
 *       records the verdict here (MACHINE). VERIFIED activates only if the contact is verified.</li>
 * </ol>
 */
@Slf4j
@Service
public class CustomerOnboardingService {

    static final String PENDING = "PENDING";

    private final PersonRepository persons;
    private final PersonProvisioning provisioning;
    private final TenantRepository tenants;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;

    public CustomerOnboardingService(PersonRepository persons, PersonProvisioning provisioning,
                                     TenantRepository tenants, ApprovalService approvals,
                                     AuditService audit, OutboxService outbox) {
        this.persons = persons;
        this.provisioning = provisioning;
        this.tenants = tenants;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Transactional
    public CustomerDtos.CustomerView create(UUID tenantId, CustomerDtos.CreateCustomer in, Actor actor) {
        if (actor.personId() == null) {
            throw ApiException.forbidden("ACTOR_TYPE_REJECTED", "Maker must be a person (four-eyes at person_id)");
        }
        Tenant tenant = tenants.findById(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Organization not found"));
        if (!"ACTIVE".equals(tenant.status)) {
            throw ApiException.conflict("TENANT_NOT_ACTIVE", "Only an active organization can onboard customers");
        }
        String first = trimmed(in.firstName());
        String last = trimmed(in.lastName());
        if (first == null || last == null) {
            throw ApiException.badRequest("CUSTOMER_FIELDS", "First and last name are required");
        }
        var contact = PersonProvisioning.contact(in.email(), in.phone(), in.primaryContact());
        if (contact.email() != null && persons.existsLiveCustomerEmail(contact.email())) {
            log.warn("customer_create_refused reason=email_taken tenant={} maker={}", tenantId, actor.personId());
            throw ApiException.conflict("CUSTOMER_EMAIL_TAKEN", "Email is already registered.");
        }
        if (contact.phone() != null && persons.existsLiveCustomerPhone(contact.phone())) {
            log.warn("customer_create_refused reason=phone_taken tenant={} maker={}", tenantId, actor.personId());
            throw ApiException.conflict("CUSTOMER_PHONE_TAKEN", "Phone number is already registered.");
        }
        // the sign-in name is the primary contact itself — what the customer knows
        String username = "EMAIL".equals(contact.primary()) ? contact.email() : contact.phone();
        Person p = provisioning.createInvitedPerson(tenantId, username, first + " " + last,
            contact.email(), contact.phone(), contact.primary(), PENDING, false, true);

        audit.record(actor.personId(), null, tenantId, null, "CUSTOMER_CREATED", "PERSON", p.id.toString(),
            null, audit.hashState("status=PENDING;channel=" + p.primaryContact), false);
        outbox.record("Person", p.id, "Customer.Created",
            "{\"personId\":\"%s\",\"tenantId\":\"%s\",\"maker\":\"%s\"}".formatted(p.id, tenantId, actor.personId()));
        log.info("customer_created personId={} tenant={} maker={} channel={}", p.id, tenantId, actor.personId(),
            p.primaryContact);
        return view(p);
    }

    @Transactional(readOnly = true)
    public CustomerDtos.CustomerView get(UUID tenantId, UUID personId) {
        return view(loadCustomer(tenantId, personId));
    }

    /** Re-send precondition: the person is in this org, was invited, and has not activated yet. */
    @Transactional(readOnly = true)
    public Person requireAwaitingActivation(UUID tenantId, UUID personId) {
        Person p = persons.findById(personId).orElse(null);
        if (p == null || !tenantId.equals(p.tenantId) || p.primaryContact == null) {
            throw ApiException.notFound("PERSON_NOT_FOUND", "Person not found in this organization");
        }
        if (p.contactVerifiedAt != null) {
            throw ApiException.conflict("ALREADY_ACTIVATED", "This person already set their password");
        }
        if ("ARCHIVED".equals(p.status) || "DISABLED".equals(p.status)) {
            throw ApiException.conflict("PERSON_STATE", "Person is " + p.status);
        }
        return p;
    }

    /**
     * The KYC verdict for a customer, backed by the plugin's decided approval (MACHINE only).
     * Mirrors TenantVerificationService: approval decided, opened by this client, same payload
     * hash, maker ≠ checker. VERIFIED additionally requires the contact to be verified.
     */
    @Transactional
    public CustomerDtos.VerificationReceipt recordVerdict(UUID tenantId, UUID personId, String verdict,
                                                          UUID approvalId, String payloadHash, Actor actor) {
        if (actor.type() != Actor.ActorType.MACHINE) {
            throw ApiException.forbidden("MACHINE_ONLY", "Only a plugin machine actor can record customer verdicts");
        }
        if (!"VERIFIED".equals(verdict) && !"REJECTED".equals(verdict)) {
            throw ApiException.badRequest("INVALID_VERDICT", "verdict must be VERIFIED or REJECTED");
        }
        Person p = loadCustomer(tenantId, personId);
        ApprovalRequest approval = approvals.findById(approvalId);
        if (approval.clientId != null && !approval.clientId.equals(actor.clientId())) {
            log.warn("customer_verdict_refused reason=approval_client_mismatch approvalId={}", approvalId);
            throw ApiException.forbidden("APPROVAL_CLIENT_MISMATCH", "Approval was not opened by this client");
        }
        if (!approval.tenantId.equals(tenantId)) {
            log.warn("customer_verdict_refused reason=approval_tenant_mismatch approvalId={}", approvalId);
            throw ApiException.forbidden("APPROVAL_TENANT_MISMATCH", "Approval does not cover this organization");
        }
        String expectedStatus = "VERIFIED".equals(verdict) ? ApprovalRequest.Status.APPROVED : ApprovalRequest.Status.REJECTED;
        if (!expectedStatus.equals(approval.status)) {
            throw ApiException.conflict("VERDICT_MISMATCH",
                "%s requires approval %s, but it is %s".formatted(verdict, expectedStatus, approval.status));
        }
        String expectedHash = approval.operationType.startsWith("PLUGIN:") ? approval.payload : approval.payloadHash;
        if (payloadHash == null || !payloadHash.equals(expectedHash)) {
            log.warn("customer_verdict_refused reason=payload_hash_mismatch approvalId={}", approvalId);
            throw ApiException.badRequest("PAYLOAD_HASH_MISMATCH", "payloadHash does not match the approval");
        }
        if (approval.makerId != null && approval.makerId.equals(approval.checkerId)) {
            throw ApiException.forbidden("MAKER_CANNOT_APPROVE", "Maker and checker cannot be the same person");
        }
        if (!PENDING.equals(p.status)) {
            // idempotent replay of the same verdict is fine; anything else is a state conflict
            boolean same = "VERIFIED".equals(verdict) ? "ACTIVE".equals(p.status) : "ARCHIVED".equals(p.status);
            if (same) return new CustomerDtos.VerificationReceipt(p.id, verdict, approvalId, p.status);
            throw ApiException.conflict("CUSTOMER_STATE", "Customer is " + p.status);
        }
        if ("VERIFIED".equals(verdict)) {
            if (p.contactVerifiedAt == null) {
                log.warn("customer_activation_refused personId={} reason=contact_not_verified", p.id);
                throw ApiException.conflict("CONTACT_NOT_VERIFIED",
                    "The customer has not completed activation on their " + channelWord(p) + " yet");
            }
            p.status = "ACTIVE";
        } else {
            p.status = "ARCHIVED";
            p.archivedAt = Instant.now();
        }
        p.updatedAt = Instant.now();
        persons.save(p); // @Version: a concurrent verdict fails instead of overwriting

        audit.record(approval.checkerId, approval.checkerId, tenantId, null, "CUSTOMER_" + verdict, "PERSON",
            p.id.toString(), audit.hashState("status=PENDING"),
            audit.hashState("status=" + p.status + ";approvalId=" + approvalId), false);
        outbox.record("Person", p.id, "VERIFIED".equals(verdict) ? "Customer.Activated" : "Customer.Rejected",
            "{\"personId\":\"%s\",\"approvalId\":\"%s\",\"checker\":\"%s\"}".formatted(p.id, approvalId, approval.checkerId));
        log.info("customer_verdict_recorded personId={} verdict={} approvalId={} checker={}",
            p.id, verdict, approvalId, approval.checkerId);
        return new CustomerDtos.VerificationReceipt(p.id, verdict, approvalId, p.status);
    }

    private Person loadCustomer(UUID tenantId, UUID personId) {
        Person p = persons.findById(personId).orElse(null);
        if (p == null || !p.isCustomer || !tenantId.equals(p.tenantId)) {
            throw ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found in this organization");
        }
        return p;
    }

    private static String channelWord(Person p) {
        return "PHONE".equals(p.primaryContact) ? "phone" : "email";
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static CustomerDtos.CustomerView view(Person p) {
        return new CustomerDtos.CustomerView(p.id, p.tenantId, p.username, p.fullName, p.status,
            p.primaryContact, p.contactVerifiedAt != null, p.contactVerifiedAt, p.version);
    }

    /** Audit: who asked the IdP to re-issue this person's activation (ids + channel only). */
    @Transactional
    public void recordActivationResent(UUID tenantId, Person p, Actor actor) {
        audit.record(actor.personId(), null, tenantId, null, "PERSON_ACTIVATION_RESENT", "PERSON", p.id.toString(),
            null, audit.hashState("channel=" + p.primaryContact + ";at=" + java.time.Instant.now()), false);
    }
}
