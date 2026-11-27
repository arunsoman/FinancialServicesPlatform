package com.fsp.core.customer;

import java.time.Instant;
import java.util.UUID;

/** Wire records for KYC-onboarded customers (V36). Never carries credentials or full contacts. */
public final class CustomerDtos {

    private CustomerDtos() {}

    /** Staff-entered identity for a new customer; the customer sets their own password later. */
    public record CreateCustomer(String firstName, String lastName, String email, String phone,
                                 String primaryContact) {}

    /** contactVerified: the customer completed activation on the primary contact. */
    public record CustomerView(UUID personId, UUID tenantId, String username, String fullName,
                               String status, String primaryContact, boolean contactVerified,
                               Instant contactVerifiedAt, long version) {}

    public record ActivationSent(UUID personId, String channel) {}

    public record VerificationReceipt(UUID personId, String verdict, UUID approvalId, String status) {}
}
