package com.fsp.plugin.kyc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * KYC case owned by the plugin (plugin-platform-plan §12.3).
 * Every row carries owner_tenant_id (installation tenant) and subject_tenant_id (applicant org).
 * The plugin never touches core's DB or TigerBeetle.
 */
@Entity
@Table(name = "kyc_cases")
public class KycCase {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REQUEST_INFO = "REQUEST_INFO";
    public static final String STATUS_TENANT_PENDING = "TENANT_PENDING";
    public static final String STATUS_STALLED = "STALLED";

    @Id
    public UUID id;

    @Column(nullable = false)
    public UUID ownerTenantId;      // installation tenant

    @Column(name = "subject_tenant_id")
    public UUID subjectTenantId;    // applicant org (from core; null until the saga created it)

    @Column(nullable = false, unique = true)
    public String caseNo;           // human ref KYC-2026-000123

    @Column(nullable = false)
    public String status;

    @Column(name = "approval_id")
    public UUID approvalId;

    @Column(name = "approval_rev")
    public Long approvalRev;

    @Column(name = "maker_person_id")
    public UUID makerPersonId;

    @Column(name = "maker_name")
    public String makerName;

    @Column(name = "submitted_at")
    public Instant submittedAt;

    @Column(name = "checker_person_id")
    public UUID checkerPersonId;

    public static final String KIND_ORGANIZATION = "ORGANIZATION";
    public static final String KIND_CUSTOMER = "CUSTOMER";

    @Column(name = "case_kind", nullable = false)
    public String caseKind = KIND_ORGANIZATION;

    // ---- CUSTOMER cases (V2): the individual's KYC record; core holds only the person ----
    @Column(name = "subject_person_id")
    public UUID subjectPersonId;
    @Column(name = "first_name")
    public String firstName;
    @Column(name = "last_name")
    public String lastName;
    public String gender;
    @Column(name = "date_of_birth")
    public LocalDate dateOfBirth;
    public String phone;
    public String email;
    @Column(name = "primary_contact")
    public String primaryContact;
    /** Cache of core's persons.contact_verified_at (core stays the PDP on activation). */
    @Column(name = "contact_verified_at")
    public Instant contactVerifiedAt;
    @Column(name = "activation_sent_at")
    public Instant activationSentAt;

    // ---- ORGANIZATION cases (V2): the org-born persons who activate by link/code ----
    @Column(name = "admin_person_id")
    public UUID adminPersonId;
    @Column(name = "checker_person_id_subject")
    public UUID subjectCheckerPersonId;

    public String subType;          // MANUFACTURER|DISTRIBUTOR|DEALER|WHOLESALER|MERCHANT

    @Column(name = "legal_name", nullable = false)
    public String legalName;

    @Column(name = "dba_name")
    public String dbaName;

    @Column(name = "entity_type")
    public String entityType;

    @Column(name = "registration_number")
    public String registrationNumber;

    @Column(name = "incorporation_date")
    public LocalDate incorporationDate;

    @Column(name = "tax_id")
    public String taxId;

    @Column(name = "mcc_code")
    public String mccCode;

    @Column(name = "website_url")
    public String websiteUrl;

    @Column(name = "expected_monthly_volume")
    public BigDecimal expectedMonthlyVolume;

    @Column(name = "avg_ticket_size")
    public BigDecimal avgTicketSize;

    @Column(name = "registered_address")
    public String registeredAddress;

    @Column(name = "registered_city")
    public String registeredCity;

    @Column(name = "registered_region")
    public String registeredRegion;

    @Column(name = "registered_postal")
    public String registeredPostal;

    @Column(name = "registered_country")
    public String registeredCountry;

    @Column(name = "operational_address_same", nullable = false)
    public boolean operationalAddressSame = true;

    @Column(name = "operational_address")
    public String operationalAddress;

    @Column(name = "operational_city")
    public String operationalCity;

    @Column(name = "operational_region")
    public String operationalRegion;

    @Column(name = "operational_postal")
    public String operationalPostal;

    @Column(name = "risk_rating")
    public String riskRating;       // LOW|MEDIUM|HIGH

    @Column(name = "next_review_date")
    public LocalDate nextReviewDate;

    // search_vector is a GENERATED column — queried in SQL only, never mapped (Hibernate must not write it)

    @Version
    public long version;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();

    protected KycCase() {}

    public KycCase(UUID id, UUID ownerTenantId, UUID subjectTenantId, String caseNo,
                   String status, String subType, String legalName, String registrationNumber) {
        this.id = id;
        this.ownerTenantId = ownerTenantId;
        this.subjectTenantId = subjectTenantId;
        this.caseNo = caseNo;
        this.status = status;
        this.subType = subType;
        this.legalName = legalName;
        this.registrationNumber = registrationNumber;
    }
}
