package com.fsp.plugin.kyc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.plugin.kyc.api.ApiProblemException;
import com.fsp.plugin.kyc.api.KycDtos;
import com.fsp.plugin.kyc.domain.IdempotencyRecord;
import com.fsp.plugin.kyc.domain.KycCase;
import com.fsp.plugin.kyc.repository.IdempotencyRecordRepository;
import com.fsp.plugin.kyc.repository.KycCaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Customer KYC (V2) — owned by the KYC plugin: the case, the individual's record and the
 * widget. Same saga shape as organization onboarding ({@link KycOnboardingSagaService}):
 * <ol>
 *   <li>case row TENANT_PENDING ("Setting up") — plugin tx;</li>
 *   <li>core creates the customer person PENDING on behalf of the maker, and the IdP sends the
 *       activation to the primary contact (kyc-customer-&lt;case&gt;);</li>
 *   <li>sanctions screening (sandbox provider);</li>
 *   <li>core opens the four-eyes approval (kyc-approval-&lt;case&gt;-1) → PENDING_REVIEW.</li>
 * </ol>
 * The checker can approve only once the customer completed activation (contact proven, own
 * password set); core re-checks that itself before it makes the customer ACTIVE.
 * No DB transaction is held across a core call (AGENTS §2d): each local step commits alone,
 * and every remote step carries a deterministic idempotency key so a retry cannot fork.
 */
@Slf4j
@Service
public class KycCustomerService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KycCaseRepository cases;
    private final KycCaseService caseService;
    private final IdempotencyRecordRepository idempotency;
    private final ScreeningService screening;
    private final CoreClient core;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public KycCustomerService(KycCaseRepository cases, KycCaseService caseService,
                              IdempotencyRecordRepository idempotency, ScreeningService screening,
                              CoreClient core, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.cases = cases;
        this.caseService = caseService;
        this.idempotency = idempotency;
        this.screening = screening;
        this.core = core;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public KycCase onboard(UUID ownerTenantId, UUID makerPersonId, String makerName,
                           KycDtos.CreateCustomerRequest req, String actorAssertion, String idempotencyKey) {
        IdempotencyRecord priorRecord = idempotency.findByPersonIdAndRequestKey(makerPersonId, idempotencyKey)
            .orElse(null);
        if (priorRecord != null) throw replayOrReject(priorRecord);

        Validated v = validate(req);

        // 1. the case exists before core is asked for the person
        KycCase created = tx.execute(s -> {
            IdempotencyRecord previous = idempotency.findByPersonIdAndRequestKey(makerPersonId, idempotencyKey)
                .orElse(null);
            if (previous != null) {
                throw replayOrReject(previous);
            }
            if (v.email() != null && openCaseWith(ownerTenantId, "lower(email) = lower(?)", v.email())) {
                throw new ApiProblemException("CUSTOMER_EMAIL_TAKEN", "Email is already registered.", 409);
            }
            if (v.phone() != null && openCaseWith(ownerTenantId, "phone = ?", v.phone())) {
                throw new ApiProblemException("CUSTOMER_PHONE_TAKEN", "Phone number is already registered.", 409);
            }
            Long seq = jdbc.queryForObject("SELECT nextval('kyc_case_no_seq')", Long.class);
            String caseNo = "KYC-" + java.time.Year.now() + "-" + String.format("%06d", seq);
            KycCase c = new KycCase(UUID.randomUUID(), ownerTenantId, null, caseNo,
                KycCase.STATUS_TENANT_PENDING, null, v.firstName() + " " + v.lastName(), null);
            c.caseKind = KycCase.KIND_CUSTOMER;
            c.firstName = v.firstName();
            c.lastName = v.lastName();
            c.gender = v.gender();
            c.dateOfBirth = v.dateOfBirth();
            c.email = v.email();
            c.phone = v.phone();
            c.primaryContact = v.primaryContact();
            // residential → the case's registered_* columns, postal → operational_* (same shape
            // as the organization's registered / operating address)
            c.registeredAddress = v.residential().line();
            c.registeredCity = v.residential().city();
            c.registeredRegion = v.residential().region();
            c.registeredPostal = v.residential().postalCode();
            c.registeredCountry = "NA";
            c.operationalAddressSame = v.postal() == null;
            if (v.postal() != null) {
                c.operationalAddress = v.postal().line();
                c.operationalCity = v.postal().city();
                c.operationalRegion = v.postal().region();
                c.operationalPostal = v.postal().postalCode();
            }
            c.makerPersonId = makerPersonId;
            c.makerName = makerName;
            c.submittedAt = Instant.now();
            cases.saveAndFlush(c);
            caseService.event(c, "CREATED", makerName, "Customer registration captured");
            IdempotencyRecord r = new IdempotencyRecord();
            r.id = UUID.randomUUID();
            r.personId = makerPersonId;
            r.requestKey = idempotencyKey;
            r.status = "DONE";
            r.responseStatus = 201;
            r.responseContentType = "application/json";
            r.responseBody = "{}".getBytes();
            r.createdAt = Instant.now();
            r.updatedAt = Instant.now();
            idempotency.save(r);
            return c;
        });
        UUID caseId = created.id;
        log.info("kyc_customer_case_created caseId={} caseNo={} ownerTenant={} maker={} channel={}",
            caseId, created.caseNo, ownerTenantId, makerPersonId, v.primaryContact());

        // 2. core: the person (PENDING, no usable password) + the activation to the primary contact
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", v.firstName());
        body.put("lastName", v.lastName());
        body.put("email", v.email());
        body.put("phone", v.phone());
        body.put("primaryContact", v.primaryContact());
        Map<String, Object> person;
        try {
            person = core.createCustomer(ownerTenantId, body, actorAssertion, "kyc-customer-" + caseId);
        } catch (ApiProblemException e) {
            if (e.getStatus() >= 400 && e.getStatus() < 500) {
                try {
                    tx.executeWithoutResult(s -> {
                        KycCase x = cases.findById(caseId).orElseThrow();
                        x.status = KycCase.STATUS_REJECTED;
                        x.updatedAt = Instant.now();
                        cases.save(x);
                        caseService.event(x, "CORE_REJECTED", "KYC plugin",
                            "Platform refused the customer: " + e.getCode());

                        IdempotencyRecord record = idempotency.findByPersonIdAndRequestKey(makerPersonId, idempotencyKey)
                            .orElseThrow(() -> new IllegalStateException("KYC idempotency record is missing"));
                        record.status = "DONE";
                        record.responseStatus = e.getStatus();
                        record.responseContentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
                        record.responseBody = problemBody(e);
                        record.updatedAt = Instant.now();
                        idempotency.save(record);
                    });
                } catch (RuntimeException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                    log.error("kyc_customer_rejection_cleanup_failed caseId={} code={}",
                        caseId, e.getCode(), cleanupFailure);
                }
                log.warn("kyc_customer_registration_core_rejected caseId={} code={}", caseId, e.getCode());
            }
            throw e;
        }
        UUID personId = UUID.fromString(String.valueOf(person.get("personId")));
        tx.executeWithoutResult(s -> {
            KycCase c = cases.findById(caseId).orElseThrow();
            c.subjectPersonId = personId;
            c.activationSentAt = Instant.now();
            c.updatedAt = Instant.now();
            cases.save(c);
            caseService.event(c, "ACTIVATION_SENT", "KYC plugin",
                "Activation sent to the customer's " + channelWord(c.primaryContact));
        });

        // 3. screening (sandbox provider decides; never the caller)
        screening.runSanctions(caseId, ownerTenantId, v.firstName() + " " + v.lastName(), List.of());

        // 4. four-eyes approval for the person
        KycCase c = cases.findById(caseId).orElseThrow();
        Map<String, Object> approval = core.openApproval(Map.of(
                "action", "CASE_DECISION", "targetResourceId", caseId.toString(), "payloadHash", payloadHash(c)),
            actorAssertion, "kyc-approval-" + caseId + "-1");
        KycCase done = tx.execute(s -> {
            KycCase x = cases.findById(caseId).orElseThrow();
            x.approvalId = UUID.fromString(String.valueOf(approval.get("id")));
            x.approvalRev = 1L;
            x.status = KycCase.STATUS_PENDING_REVIEW;
            x.updatedAt = Instant.now();
            x = cases.save(x);
            caseService.event(x, "SUBMITTED", makerName,
                "Awaiting the customer's activation and a second reviewer");
            return x;
        });
        log.info("kyc_customer_submitted caseId={} person={} approvalId={} maker={}",
            caseId, personId, done.approvalId, makerPersonId);
        return done;
    }

    /**
     * Pull the customer's activation state from core (the PDP) into the case cache. Cheap
     * no-op once verified. Failures are logged and the cached state is kept (display only —
     * the decision path re-checks with {@link #requireContactVerified}).
     */
    public KycCase refresh(KycCase c, String actorAssertion) {
        if (!KycCase.KIND_CUSTOMER.equals(c.caseKind) || c.subjectPersonId == null || c.contactVerifiedAt != null) {
            return c;
        }
        try {
            Instant verifiedAt = fetchVerifiedAt(c, actorAssertion);
            if (verifiedAt == null) return c;
            return markVerified(c.id, verifiedAt);
        } catch (ApiProblemException e) {
            log.warn("kyc_customer_refresh_failed caseId={} code={}", c.id, e.getCode());
            return c;
        }
    }

    /** Decision guard: core must say the customer activated (own password, contact proven). */
    public void requireContactVerified(KycCase c, String actorAssertion) {
        if (c.contactVerifiedAt != null) return;
        Instant verifiedAt = c.subjectPersonId == null ? null : fetchVerifiedAt(c, actorAssertion);
        if (verifiedAt == null) {
            log.warn("kyc_customer_approve_refused caseId={} reason=contact_not_verified", c.id);
            throw new ApiProblemException("CONTACT_NOT_VERIFIED",
                "The customer has not activated from the " + channelWord(c.primaryContact)
                    + " we sent yet — they must set their password before you can activate them", 409);
        }
        markVerified(c.id, verifiedAt);
    }

    /**
     * Re-send the activation: the customer (CUSTOMER) or the not-yet-activated admin/checker of
     * the new organization (ORGANIZATION). The newest link/code supersedes the earlier ones.
     */
    public KycDtos.ActivationResent resend(UUID caseId, UUID ownerTenantId, UUID makerPersonId, String makerName,
                                           String actorAssertion, String idempotencyKey) {
        KycCase c = cases.findByIdAndOwnerTenantId(caseId, ownerTenantId)
            .orElseThrow(() -> new ApiProblemException("KYC_CASE_NOT_FOUND", "Case not found or not accessible", 404));
        if (KycCase.STATUS_REJECTED.equals(c.status)) {
            throw new ApiProblemException("KYC_STATE", "The case was rejected", 409);
        }
        List<String> sent = new ArrayList<>();
        String keyBase = "kyc-activation-" + c.id + "-" + idempotencyKey;
        if (KycCase.KIND_CUSTOMER.equals(c.caseKind)) {
            if (c.subjectPersonId == null) {
                throw new ApiProblemException("KYC_STATE", "The customer is still being set up", 409);
            }
            Map<String, Object> r = core.resendActivation(ownerTenantId, c.subjectPersonId, actorAssertion, keyBase);
            sent.add(String.valueOf(r.get("channel")));
        } else {
            if (c.subjectTenantId == null) {
                throw new ApiProblemException("KYC_STATE", "The organization is still being set up", 409);
            }
            int i = 0;
            for (UUID p : new UUID[] { c.adminPersonId, c.subjectCheckerPersonId }) {
                i++;
                if (p == null) continue;
                try {
                    Map<String, Object> r = core.resendActivation(c.subjectTenantId, p, actorAssertion, keyBase + "-" + i);
                    sent.add(String.valueOf(r.get("channel")));
                } catch (ApiProblemException e) {
                    if (!"ALREADY_ACTIVATED".equals(e.getCode())) throw e; // that one is done — skip it
                }
            }
            if (sent.isEmpty()) {
                throw new ApiProblemException("ALREADY_ACTIVATED", "Everyone on this case has already activated", 409);
            }
        }
        tx.executeWithoutResult(s -> {
            KycCase x = cases.findById(caseId).orElseThrow();
            x.activationSentAt = Instant.now();
            cases.save(x);
            caseService.event(x, "ACTIVATION_RESENT", makerName, "Activation re-sent");
        });
        log.info("kyc_activation_resent caseId={} kind={} by={} count={}", c.id, c.caseKind, makerPersonId, sent.size());
        return new KycDtos.ActivationResent(c.id, sent);
    }

    /** Same plugin-attested hash on open and on verdict (core compares them). */
    public static String payloadHash(KycCase c) {
        return sha256(c.caseNo + "|CUSTOMER|" + c.firstName + "|" + c.lastName + "|" + c.dateOfBirth + "|" + c.subjectPersonId);
    }

    // ---------------- helpers ----------------

    private Instant fetchVerifiedAt(KycCase c, String actorAssertion) {
        Map<String, Object> view = core.getCustomer(c.ownerTenantId, c.subjectPersonId, actorAssertion);
        if (!Boolean.TRUE.equals(view.get("contactVerified"))) return null;
        Object at = view.get("contactVerifiedAt");
        try {
            return at == null ? Instant.now() : Instant.parse(String.valueOf(at));
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    private KycCase markVerified(UUID caseId, Instant verifiedAt) {
        return tx.execute(s -> {
            KycCase x = cases.findById(caseId).orElseThrow();
            if (x.contactVerifiedAt != null) return x;
            x.contactVerifiedAt = verifiedAt;
            x.updatedAt = Instant.now();
            x = cases.save(x);
            caseService.event(x, "CONTACT_VERIFIED", "Customer",
                "Customer activated from their " + channelWord(x.primaryContact) + " and set a password");
            log.info("kyc_customer_contact_verified caseId={} person={}", x.id, x.subjectPersonId);
            return x;
        });
    }

    private boolean openCaseWith(UUID owner, String predicate, String value) {
        Boolean b = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM kyc_cases WHERE owner_tenant_id = ? "
            + "AND case_kind = 'CUSTOMER' AND status NOT IN ('REJECTED', 'STALLED') AND " + predicate + ")",
            Boolean.class, owner, value);
        return Boolean.TRUE.equals(b);
    }

    private static ApiProblemException replayOrReject(IdempotencyRecord record) {
        if ("DONE".equals(record.status) && record.responseStatus != null
                && record.responseStatus >= 400 && record.responseStatus < 500 && record.responseBody != null) {
            try {
                JsonNode body = MAPPER.readTree(record.responseBody);
                String code = body.path("code").asText(body.path("title").asText("IDEMPOTENCY_ERROR"));
                String detail = body.path("detail").asText("The original request was rejected");
                return new ApiProblemException(code, detail, record.responseStatus);
            } catch (IOException e) {
                log.error("kyc_customer_idempotency_response_invalid status={}", record.responseStatus, e);
                return new ApiProblemException("IDEMPOTENCY_RESPONSE_INVALID",
                    "The original response could not be replayed", 500);
            }
        }
        return new ApiProblemException("IDEMPOTENCY_KEY_REUSED", "Idempotency key already used", 409);
    }

    private static byte[] problemBody(ApiProblemException e) {
        try {
            return MAPPER.writeValueAsBytes(e.toProblemDetails());
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Could not serialize the KYC problem response", serializationFailure);
        }
    }

    record Address(String line, String city, String region, String postalCode) {}

    /** postal == null: the postal address is the residential address. */
    record Validated(String firstName, String lastName, String gender, LocalDate dateOfBirth,
                     String email, String phone, String primaryContact, Address residential, Address postal) {}

    static Validated validate(KycDtos.CreateCustomerRequest req) {
        if (req == null) throw new ApiProblemException("KYC_VALIDATION", "Request body required", 400);
        Map<String, String> errors = new LinkedHashMap<>();
        String first = trim(req.firstName());
        String last = trim(req.lastName());
        if (first == null) errors.put("firstName", "First name is required");
        else if (first.length() > 100) errors.put("firstName", "First name is too long");
        if (last == null) errors.put("lastName", "Last name is required");
        else if (last.length() > 100) errors.put("lastName", "Last name is too long");
        if (!KycMasterService.isValid("GENDER", req.gender())) errors.put("gender", "Select a gender");
        LocalDate dob = req.dateOfBirth();
        if (dob == null) errors.put("dateOfBirth", "Date of birth is required");
        else if (!dob.isBefore(LocalDate.now())) errors.put("dateOfBirth", "Date of birth must be in the past");
        else if (dob.isBefore(LocalDate.now().minusYears(120))) errors.put("dateOfBirth", "Check the date of birth");
        String email = trim(req.email());
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 254) {
                errors.put("email", "Enter a valid email address");
            }
        }
        String phone = trim(req.phone());
        if (phone != null) {
            String compact = phone.replaceAll("[\\s()\\-.]", "");
            if (compact.startsWith("00")) compact = "+" + compact.substring(2);
            if (!compact.matches("^\\+[1-9]\\d{7,14}$")) {
                errors.put("phone", "Use international format, e.g. +264 81 123 4567");
            }
            phone = compact;
        }
        String primary = trim(req.primaryContact());
        primary = primary == null ? (email != null ? "EMAIL" : "PHONE") : primary.toUpperCase(Locale.ROOT);
        if (!primary.equals("EMAIL") && !primary.equals("PHONE")) {
            errors.put("primaryContact", "Choose email or phone");
        } else if (primary.equals("EMAIL") && email == null) {
            errors.put("email", "Email is required — it is the primary contact");
        } else if (primary.equals("PHONE") && phone == null) {
            errors.put("phone", "Phone number is required — it is the primary contact");
        }
        Address residential = address(req.residentialAddress(), "residentialAddress", "residential", errors);
        boolean postalSame = req.postalSameAsResidential() == null || req.postalSameAsResidential();
        Address postal = postalSame ? null : address(req.postalAddress(), "postalAddress", "postal", errors);
        if (!errors.isEmpty()) {
            throw new ApiProblemException("KYC_VALIDATION", String.join("; ", errors.values()), 400);
        }
        return new Validated(first, last, req.gender(), dob, email, phone, primary, residential, postal);
    }

    /** Street + town required, region from the master list, postal code optional (≤ 10). */
    static Address address(KycDtos.AddressRequest a, String field, String word, Map<String, String> errors) {
        String line = a == null ? null : trim(a.line());
        String city = a == null ? null : trim(a.city());
        String region = a == null ? null : trim(a.region());
        String postal = a == null ? null : trim(a.postalCode());
        if (line == null || line.length() < 4) errors.put(field + ".line", "Enter the " + word + " street address");
        else if (line.length() > 200) errors.put(field + ".line", "The " + word + " street address is too long");
        if (city == null || city.length() < 2) errors.put(field + ".city", "Enter the " + word + " town or city");
        else if (city.length() > 100) errors.put(field + ".city", "The " + word + " town is too long");
        if (!KycMasterService.isValid("REGION", region)) errors.put(field + ".region", "Choose the " + word + " region");
        if (postal != null && postal.length() > 10) errors.put(field + ".postalCode", "The " + word + " postal code is too long");
        return new Address(line, city, region, postal);
    }

    static String channelWord(String primaryContact) {
        return "PHONE".equals(primaryContact) ? "phone (SMS code)" : "email (link)";
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return java.util.Base64.getEncoder().encodeToString(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
