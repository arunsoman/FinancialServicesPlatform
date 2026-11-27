package com.fsp.core.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "persons")
public class Person {

    @Id
    public UUID id;
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;
    @Column(nullable = false, unique = true)
    public String username;
    @Column(name = "full_name", nullable = false)
    public String fullName;
    public String email;
    public String phone;
    @Column(name = "password_hash", nullable = false)
    public String passwordHash;
    /** F-8: born with the bootstrap password → the IdP forces a first-login change. */
    @Column(name = "password_must_change", nullable = false)
    public boolean mustChangePassword;
    @Column(nullable = false)
    public String status; // ACTIVE | ARCHIVED | DISABLED
    @Column(name = "is_applicant", nullable = false)
    public boolean isApplicant;
    /** May resolve four-eyes approvals (docs/checker-flag.md). Checked in ApprovalService. */
    @Column(name = "is_checker", nullable = false)
    public boolean isChecker;
    /** V36: a KYC-onboarded customer (not staff) — excluded from staff user lists. */
    @Column(name = "is_customer", nullable = false)
    public boolean isCustomer;
    /** V36: EMAIL | PHONE — the channel the activation is sent on (customers only). */
    @Column(name = "primary_contact")
    public String primaryContact;
    /** V36: stamped by the IdP when the customer completed activation on the primary contact. */
    @Column(name = "contact_verified_at")
    public Instant contactVerifiedAt;
    @Column(name = "archived_at")
    public Instant archivedAt;
    @Version
    @Column(nullable = false)
    public long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
