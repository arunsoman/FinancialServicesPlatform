package com.fsp.plugin.kyc.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Wire DTOs for the KYC plugin API (§12.4). Records grouped in a final container — never sealed. */
public final class KycDtos {

    private KycDtos() {}

    // ============ Case list ============
    public record CaseSummary(
        UUID caseId, String caseNo, UUID subjectTenantId, String legalName, String dbaName,
        String subType, String registrationNumber, String status, String riskRating,
        Instant submittedAt, LocalDate nextReviewDate, String makerName, Instant updatedAt, long version,
        boolean stalled,
        // V2: ORGANIZATION | CUSTOMER; for customers whether they completed activation
        String caseKind, String primaryContact, Boolean contactVerified
    ) {}

    public record CaseListResponse(
        List<CaseSummary> items, int page, int size, boolean hasNext, Long nextAfter,
        String sort, String q
    ) {}

    // ============ Case view (full) ============
    public record CaseView(
        UUID caseId, UUID ownerTenantId, UUID subjectTenantId, String caseNo, String status,
        String subType, String legalName, String dbaName, String entityType,
        String registrationNumber, LocalDate incorporationDate, String taxId,
        // money: minor units as STRINGS on the wire (AGENTS §3.2), currency NAD
        String mccCode, String websiteUrl, String expectedMonthlyVolume, String avgTicketSize, String currency,
        String registeredAddress, String registeredCity, String registeredRegion,
        String registeredPostal, String registeredCountry, boolean operationalAddressSame,
        String operationalAddress, String operationalCity, String operationalRegion,
        String operationalPostal, String riskRating, LocalDate nextReviewDate,
        UUID approvalId, Long approvalRev, UUID makerPersonId, String makerName, UUID checkerPersonId,
        Instant submittedAt, Instant createdAt, Instant updatedAt, long version, boolean stalled, boolean piiMasked,
        boolean canDecide, String blockedReason,
        List<CheckView> checks, List<UboView> ubos, List<BankAccountView> bankAccounts,
        // V2: CUSTOMER cases carry the individual (null for organizations)
        String caseKind, CustomerView customer
    ) {}

    /** The individual on a CUSTOMER case. Contacts are masked; contactVerified gates approval. */
    public record CustomerView(UUID personId, String firstName, String lastName, String gender,
                               LocalDate dateOfBirth, String email, String phone, String primaryContact,
                               boolean contactVerified, Instant contactVerifiedAt, Instant activationSentAt) {}

    public record CheckView(UUID checkId, String checkType, String result, String details,
                            boolean simulated, Instant checkedAt) {}

    public record UboView(UUID id, int personNo, String fullName, String idDocType, String idNumber,
                          BigDecimal ownershipPct, boolean isPep) {}

    public record BankAccountView(UUID id, String holderName, String bankName, String branchCode,
                                  String accountNumber, String accountType, String status) {}

    // ============ Create / Update ============
    public record CreateCaseRequest(
        String subType, String legalName, String dbaName, String entityType,
        String registrationNumber, LocalDate incorporationDate, String taxId,
        // money: minor-unit strings ("25000000" = NAD 250 000.00)
        String mccCode, String websiteUrl, String expectedMonthlyVolume, String avgTicketSize,
        String registeredAddress, String registeredCity, String registeredRegion,
        String registeredPostal, String registeredCountry, Boolean operationalAddressSame,
        String operationalAddress, String operationalCity, String operationalRegion,
        String operationalPostal,
        List<UboRequest> ubos,
        List<BankAccountRequest> bankAccounts,
        /** V2: who runs the new organization — each activates by link/code on their primary contact. */
        ContactRequest admin, ContactRequest checker,
        /** REFUSED when present (400 SCREENING_NOT_CLIENT_SETTABLE) — kept only so a client that
         *  still sends it is told so, instead of the field being silently ignored. */
        String screeningOutcome
    ) {}

    /** V2: an org-born person (administrator / checker) — the activation goes to primaryContact. */
    public record ContactRequest(String fullName, String email, String phone, String primaryContact) {}

    /** V2: staff-entered customer registration (Customer Registration, step 1 of 2). No password:
     *  the customer sets it from the activation sent to the primary contact (step 2 = checker). */
    public record CreateCustomerRequest(String firstName, String lastName, String gender, LocalDate dateOfBirth,
                                        String phone, String email, String primaryContact,
                                        AddressRequest residentialAddress, Boolean postalSameAsResidential,
                                        AddressRequest postalAddress) {}

    /** A Namibian address, as the organization application captures it (region from the REGION master). */
    public record AddressRequest(String line, String city, String region, String postalCode) {}

    public record ActivationResent(UUID caseId, List<String> channels) {}

    public record UboRequest(
        String fullName, LocalDate dob, String nationality, String residentialAddress,
        String phone, String email, String idDocType, String idNumber, LocalDate idExpiry,
        BigDecimal ownershipPct, Boolean isPep
    ) {}

    public record BankAccountRequest(
        String holderName, String bankName, String branchCode, String accountNumber, String accountType
    ) {}

    public record UpdateCaseRequest(
        String legalName, String dbaName, String entityType, String registrationNumber,
        LocalDate incorporationDate, String taxId, String mccCode, String websiteUrl,
        String expectedMonthlyVolume, String avgTicketSize,
        String registeredAddress, String registeredCity, String registeredRegion,
        String registeredPostal, String registeredCountry, Boolean operationalAddressSame,
        String operationalAddress, String operationalCity, String operationalRegion,
        String operationalPostal,
        List<UboRequest> ubos, List<BankAccountRequest> bankAccounts, Long expectedVersion
    ) {}

    // ============ Decide ============
    public record DecisionBody(String decision, String reason, Long expectedVersion) {
        public enum DecisionValue { APPROVE, REJECT, REQUEST_INFO }
    }

    // ============ Organizations ============
    public record OrganizationRow(
        UUID tenantId, String legalName, String subType, String status,
        String verificationStatus, Integer depth, UUID parentTenantId,
        UUID caseId, String caseStatus, String riskRating, LocalDate nextReviewDate
    ) {}

    public record OrganizationListResponse(
        List<OrganizationRow> items, int page, int size, boolean hasNext, Long nextAfter
    ) {}

    // ============ Master values ============
    public record MasterValue(String category, String code, String label) {}

    public record MasterListResponse(List<MasterValue> items) {}

    // ============ History ============
    public record CaseEvent(UUID id, String eventType, String payload, Instant createdAt) {}

    public record CaseHistoryResponse(UUID caseId, List<CaseEvent> events) {}

    public record SubmitBody(Long expectedVersion) {}

    /** Minor-unit string → integer amount; rejects fractions/negatives (400 KYC_AMOUNT). */
    public static BigDecimal minor(String v, String field) {
        if (v == null || v.isBlank()) return null;
        if (!v.matches("\\d{1,20}")) {
            throw new ApiProblemException("KYC_AMOUNT", field + " must be minor units as a digit string", 400);
        }
        return new BigDecimal(v);
    }

    public static String minor(BigDecimal v) {
        return v == null ? null : v.toBigInteger().toString();
    }
}
