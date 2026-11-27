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
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Core is the PDP for customer activation (V36): a checker's APPROVED verdict activates the
 * customer ONLY after they activated themselves (own password, contact proven); the verdict
 * must come from the plugin's machine client with the approval it opened.
 */
class CustomerOnboardingServiceTest {

    private final PersonRepository persons = mock(PersonRepository.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final CustomerOnboardingService service = new CustomerOnboardingService(persons,
        mock(PersonProvisioning.class), mock(TenantRepository.class), approvals, mock(AuditService.class),
        mock(OutboxService.class));

    private final UUID tenant = UUID.randomUUID();
    private final Actor machine = new Actor(null, "plugin-kyc", tenant, Actor.ActorType.MACHINE, "plugin-kyc");

    private Person customer(boolean activated) {
        Person p = new Person();
        p.id = UUID.randomUUID();
        p.tenantId = tenant;
        p.isCustomer = true;
        p.status = "PENDING";
        p.primaryContact = "EMAIL";
        p.contactVerifiedAt = activated ? Instant.now() : null;
        when(persons.findById(p.id)).thenReturn(Optional.of(p));
        return p;
    }

    private UUID approval(String status) {
        ApprovalRequest a = new ApprovalRequest();
        a.id = UUID.randomUUID();
        a.tenantId = tenant;
        a.operationType = "PLUGIN:kyc:CASE_DECISION";
        a.payload = "hash";
        a.clientId = "plugin-kyc";
        a.makerId = UUID.randomUUID();
        a.checkerId = UUID.randomUUID();
        a.status = status;
        when(approvals.findById(a.id)).thenReturn(a);
        return a.id;
    }

    @Test
    void anApprovedCustomerWhoHasNotActivatedStaysPending() {
        Person p = customer(false);
        UUID a = approval(ApprovalRequest.Status.APPROVED);
        assertThatThrownBy(() -> service.recordVerdict(tenant, p.id, "VERIFIED", a, "hash", machine))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CONTACT_NOT_VERIFIED"));
        assertThat(p.status).isEqualTo("PENDING");
        verify(persons, never()).save(any());
    }

    @Test
    void anActivatedCustomerBecomesActiveOnTheVerdict() {
        Person p = customer(true);
        UUID a = approval(ApprovalRequest.Status.APPROVED);
        var r = service.recordVerdict(tenant, p.id, "VERIFIED", a, "hash", machine);
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(p.status).isEqualTo("ACTIVE");
    }

    @Test
    void onlyThePluginMachineClientRecordsVerdicts() {
        Person p = customer(true);
        UUID a = approval(ApprovalRequest.Status.APPROVED);
        Actor person = new Actor(UUID.randomUUID(), "someone", tenant);
        assertThatThrownBy(() -> service.recordVerdict(tenant, p.id, "VERIFIED", a, "hash", person))
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("MACHINE_ONLY"));
    }

    @Test
    void aTamperedPayloadHashIsRefused() {
        Person p = customer(true);
        UUID a = approval(ApprovalRequest.Status.APPROVED);
        assertThatThrownBy(() -> service.recordVerdict(tenant, p.id, "VERIFIED", a, "other", machine))
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PAYLOAD_HASH_MISMATCH"));
        assertThat(p.status).isEqualTo("PENDING");
    }

    @Test
    void aRejectedVerdictClosesTheCustomer() {
        Person p = customer(false);
        UUID a = approval(ApprovalRequest.Status.REJECTED);
        service.recordVerdict(tenant, p.id, "REJECTED", a, "hash", machine);
        assertThat(p.status).isEqualTo("ARCHIVED");
    }

    @Test
    void contactRules() {
        assertThat(PersonProvisioning.contact(" Jane@Example.COM ", null, null).primary()).isEqualTo("EMAIL");
        assertThat(PersonProvisioning.contact(null, "+264 81 123 4567", "phone").phone()).isEqualTo("+264811234567");
        assertThatThrownBy(() -> PersonProvisioning.contact("a@b.co", null, "PHONE"))
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CONTACT_REQUIRED"));
        assertThatThrownBy(() -> PersonProvisioning.contact(null, "081 123", "PHONE"))
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PHONE_INVALID"));
    }
}
