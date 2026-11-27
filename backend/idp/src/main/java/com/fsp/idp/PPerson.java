package com.fsp.idp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/** Read-only projection of a canonical person — the credential store row owned by the IdP. */
@Entity
@Table(name = "persons")
public class PPerson {

    @Id
    public UUID id;
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;
    @Column(nullable = false, unique = true)
    public String username;
    @Column(name = "full_name", nullable = false)
    public String fullName;
    @Column(name = "password_hash", nullable = false)
    public String passwordHash;
    /** F-8: born with the bootstrap password → first sign-in must set a new one. */
    @Column(name = "password_must_change", nullable = false)
    public boolean mustChangePassword;
    @Column(nullable = false)
    public String status;
    @Column(name = "is_applicant", nullable = false)
    public boolean isApplicant;

    // read-only contacts of the canonical person (V30): auth-plane targets, never writable here
    @Column
    public String email;
    @Column
    public String phone;

    /** V36: EMAIL | PHONE — where the activation goes; null for persons born before V36. */
    @Column(name = "primary_contact")
    public String primaryContact;
    /** V36: stamped HERE when the person completed activation (set their own password). */
    @Column(name = "contact_verified_at")
    public java.time.Instant contactVerifiedAt;
    @Column(name = "is_customer", nullable = false)
    public boolean isCustomer;

    /** Optimistic-lock passthrough on the canonical row: IdP writes (password reset,
     *  hosted change) compare-and-swap on persons.version exactly like core does —
     *  a concurrent core update to the same person fails HERE instead of being clobbered. */
    @Version
    @Column(nullable = false)
    public long version;
}
