package com.fsp.plugin.kyc.service;

import com.fsp.plugin.kyc.api.ApiProblemException;
import com.fsp.plugin.kyc.api.KycDtos;
import com.fsp.plugin.kyc.domain.IdempotencyRecord;
import com.fsp.plugin.kyc.domain.KycBankAccount;
import com.fsp.plugin.kyc.domain.KycCase;
import com.fsp.plugin.kyc.domain.KycCaseEvent;
import com.fsp.plugin.kyc.domain.KycUbo;
import com.fsp.plugin.kyc.domain.NamibiaRules;
import com.fsp.plugin.kyc.repository.IdempotencyRecordRepository;
import com.fsp.plugin.kyc.repository.KycCaseEventRepository;
import com.fsp.plugin.kyc.repository.KycCaseRepository;
import com.fsp.plugin.kyc.repository.KycCheckRepository;
import com.fsp.plugin.kyc.repository.KycUboRepository;
import com.fsp.plugin.kyc.repository.KycBankAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KYC case CRUD service (plugin-platform-plan §12.4).
 * All mutations carry deterministic idempotency keys derived from the case id.
 * Optimistic locking with expectedVersion on updates.
 */
@Slf4j
@Service
public class KycCaseService {

    private final KycCaseRepository caseRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final CoreClient core;
    private final KycCheckRepository checkRepository;
    private final KycUboRepository uboRepository;
    private final KycBankAccountRepository bankRepository;
    private final KycCaseEventRepository eventRepository;
    private final IdempotencyRecordRepository idempotencyRepository;

    public KycCaseService(KycCaseRepository caseRepository, KycCheckRepository checkRepository,
                          KycUboRepository uboRepository, KycBankAccountRepository bankRepository,
                          KycCaseEventRepository eventRepository,
                          IdempotencyRecordRepository idempotencyRepository,
                          org.springframework.jdbc.core.JdbcTemplate jdbc, CoreClient core) {
        this.jdbc = jdbc;
        this.core = core;
        this.caseRepository = caseRepository;
        this.checkRepository = checkRepository;
        this.uboRepository = uboRepository;
        this.bankRepository = bankRepository;
        this.eventRepository = eventRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    /** Public sort names → pinned SQL columns (AGENTS §4A: never a client-supplied column). */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "submittedAt", "submitted_at", "legalName", "lower(legal_name)", "status", "status",
        "riskRating", "CASE risk_rating WHEN 'LOW' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'HIGH' THEN 3 END",
        "nextReviewDate", "next_review_date", "updatedAt", "updated_at");
    private static final java.util.Set<String> STATUSES = java.util.Set.of(
        KycCase.STATUS_DRAFT, KycCase.STATUS_TENANT_PENDING, KycCase.STATUS_SUBMITTED,
        KycCase.STATUS_PENDING_REVIEW, KycCase.STATUS_REQUEST_INFO, KycCase.STATUS_APPROVED,
        KycCase.STATUS_REJECTED, KycCase.STATUS_STALLED);
    private static final java.util.Set<String> KINDS = java.util.Set.of(KycCase.KIND_ORGANIZATION, KycCase.KIND_CUSTOMER);
    private static final java.util.Set<String> RISKS = java.util.Set.of("LOW", "MEDIUM", "HIGH");

    /**
     * Server-side paging, search and sorting (plan §12.4) — totals-free: fetch size+1 to know
     * whether a next page exists. Search = full text OR prefix on registration no / TIN / case no.
     * Scoped to the installation tenant always.
     */
    @Transactional(readOnly = true)
    public KycDtos.CaseListResponse list(UUID ownerTenantId, int page, int size,
                                         String q, String status, String risk, String kind, String sort) {
        if (size < 1 || size > 100) throw new ApiProblemException("PAGE_SIZE_INVALID", "size must be 1-100", 400);
        if (page < 0) throw new ApiProblemException("PAGE_INVALID", "page must be ≥ 0", 400);
        String[] sp = (sort == null || sort.isBlank() ? "submittedAt,desc" : sort).split(",");
        String column = SORT_COLUMNS.get(sp[0]);
        String dir = sp.length > 1 ? sp[1].toLowerCase() : "asc";
        if (column == null || !(dir.equals("asc") || dir.equals("desc"))) {
            throw new ApiProblemException("SORT_INVALID", "unknown sort " + sort, 400);
        }
        StringBuilder sql = new StringBuilder("SELECT id FROM kyc_cases WHERE owner_tenant_id = ?");
        List<Object> args = new ArrayList<>(List.of(ownerTenantId));
        String term = q == null ? "" : q.trim();
        if (!term.isEmpty()) {
            if (term.length() < 2 || term.length() > 100) {
                throw new ApiProblemException("SEARCH_INVALID", "search needs 2-100 characters", 400);
            }
            String like = term.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (search_vector @@ plainto_tsquery('english', ?) OR lower(legal_name) LIKE ? ")
               .append("OR lower(registration_number) LIKE ? OR lower(coalesce(tax_id,'')) LIKE ? OR lower(case_no) LIKE ? OR lower(coalesce(email,'')) LIKE ? OR coalesce(phone,'') LIKE ?)");
            args.add(term);
            args.add("%" + like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        List<String> st = csv(status, STATUSES, "status");
        if (!st.isEmpty()) {
            sql.append(" AND status IN (").append("?,".repeat(st.size()).replaceAll(",$", "")).append(")");
            args.addAll(st);
        }
        List<String> kd = csv(kind, KINDS, "kind");
        if (!kd.isEmpty()) {
            sql.append(" AND case_kind IN (").append("?,".repeat(kd.size()).replaceAll(",$", "")).append(")");
            args.addAll(kd);
        }
        List<String> rk = csv(risk, RISKS, "risk");
        if (!rk.isEmpty()) {
            sql.append(" AND risk_rating IN (").append("?,".repeat(rk.size()).replaceAll(",$", "")).append(")");
            args.addAll(rk);
        }
        sql.append(" ORDER BY ").append(column).append(' ').append(dir).append(" NULLS LAST, id LIMIT ? OFFSET ?");
        args.add(size + 1);
        args.add((long) page * size);

        List<UUID> ids = jdbc.queryForList(sql.toString(), UUID.class, args.toArray());
        boolean hasNext = ids.size() > size;
        List<UUID> pageIds = hasNext ? ids.subList(0, size) : ids;
        Map<UUID, KycCase> byId = new LinkedHashMap<>();
        caseRepository.findAllById(pageIds).forEach(c -> byId.put(c.id, c));
        List<KycDtos.CaseSummary> items = pageIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
            .map(this::toSummary).toList();
        return new KycDtos.CaseListResponse(items, page, size, hasNext, hasNext ? (long) page + 1 : null,
            sp[0] + "," + dir, term.isEmpty() ? null : term);
    }

    private static List<String> csv(String raw, java.util.Set<String> allowed, String name) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String v : raw.split(",")) {
            if (!allowed.contains(v)) throw new ApiProblemException("FILTER_INVALID", "unknown " + name + " " + v, 400);
            out.add(v);
        }
        return out;
    }

    /** Domain history for the UI (kyc_case_events), oldest first. */
    @Transactional(readOnly = true)
    public KycDtos.CaseHistoryResponse history(UUID caseId, UUID ownerTenantId) {
        caseRepository.findByIdAndOwnerTenantId(caseId, ownerTenantId)
            .orElseThrow(() -> new ApiProblemException("KYC_CASE_NOT_FOUND", "Case not found or not accessible", 404));
        return new KycDtos.CaseHistoryResponse(caseId, eventRepository.findByCaseIdOrderByCreatedAtAsc(caseId).stream()
            .map(e -> new KycDtos.CaseEvent(e.id, e.eventType, e.payload, e.createdAt)).toList());
    }

    @Transactional(readOnly = true)
    public java.util.Optional<KycCase> find(UUID caseId, UUID ownerTenantId) {
        return caseRepository.findByIdAndOwnerTenantId(caseId, ownerTenantId);
    }

    @Transactional(readOnly = true)
    public KycDtos.CaseView get(UUID caseId, UUID ownerTenantId, UUID viewerPersonId, String viewerAssertion) {
        KycCase c = caseRepository.findByIdAndOwnerTenantId(caseId, ownerTenantId)
            .orElseThrow(() -> new ApiProblemException("KYC_CASE_NOT_FOUND",
                "Case not found or not accessible", 404));

        List<KycDtos.CheckView> checks = checkRepository.findByCaseIdOrderByCheckedAtDesc(c.id).stream()
            .map(ch -> new KycDtos.CheckView(ch.id, ch.checkType, ch.result, ch.details, ch.simulated, ch.checkedAt))
            .toList();

        List<KycDtos.UboView> ubos = uboRepository.findByCaseIdOrderByPersonNoAsc(c.id).stream()
            .map(u -> new KycDtos.UboView(u.id, u.personNo, u.fullName, u.idDocType, mask(u.idNumber),
                u.ownershipPct, u.isPep)).toList();

        List<KycDtos.BankAccountView> banks = bankRepository.findByCaseIdOrderByCreatedAtAsc(c.id).stream()
            .map(b -> new KycDtos.BankAccountView(b.id, b.holderName, b.bankName, b.branchCode,
                mask(b.accountNumber), b.accountType, b.status)).toList();

        // Per VIEWER (ui-std §5.2): the maker never gets decision controls. Core re-enforces
        // four-eyes and the checker permission on the decision itself — this is display only.
        String blockedReason = null;
        if (!KycCase.STATUS_PENDING_REVIEW.equals(c.status)) {
            blockedReason = "STATE";
        } else if (viewerPersonId != null && viewerPersonId.equals(c.makerPersonId)) {
            blockedReason = "MAKER";
        } else if (viewerPersonId != null && !isChecker(viewerAssertion)) {
            blockedReason = "CHECKER";
        }
        boolean canDecide = blockedReason == null;

        return new KycDtos.CaseView(
            c.id, c.ownerTenantId, c.subjectTenantId, c.caseNo, c.status, c.subType,
            c.legalName, c.dbaName, c.entityType, c.registrationNumber, c.incorporationDate,
            c.taxId, c.mccCode, c.websiteUrl, KycDtos.minor(c.expectedMonthlyVolume), KycDtos.minor(c.avgTicketSize), "NAD",
            c.registeredAddress, c.registeredCity, c.registeredRegion, c.registeredPostal,
            c.registeredCountry, c.operationalAddressSame, c.operationalAddress,
            c.operationalCity, c.operationalRegion, c.operationalPostal,
            c.riskRating, c.nextReviewDate, c.approvalId, c.approvalRev,
            c.makerPersonId, c.makerName, c.checkerPersonId, c.submittedAt, c.createdAt, c.updatedAt, c.version,
            isStalled(c), true, canDecide, blockedReason, checks, ubos, banks,
            c.caseKind, customerView(c)
        );
    }

    @Transactional
    public KycCase create(UUID ownerTenantId, UUID makerPersonId, String makerName,
                          KycDtos.CreateCaseRequest req, String idempotencyKey) {
        if (idempotencyRepository.findByPersonIdAndRequestKey(makerPersonId, idempotencyKey).isPresent()) {
            throw new ApiProblemException("IDEMPOTENCY_KEY_REUSED", "Idempotency key already used", 409);
        }

        validateCreate(req);

        if (caseRepository.existsActiveRegistration(ownerTenantId,
                NamibiaRules.normalizeRegistration(req.registrationNumber()))) {
            throw new ApiProblemException("REGISTRATION_TAKEN",
                "An open or approved case already uses this registration number", 409);
        }
        Long seq = jdbc.queryForObject("SELECT nextval('kyc_case_no_seq')", Long.class);
        String caseNo = "KYC-" + java.time.Year.now() + "-" + String.format("%06d", seq);

        // Saga step 1: the case exists before core is asked for the org (TENANT_PENDING)
        KycCase c = new KycCase(UUID.randomUUID(), ownerTenantId, null, caseNo,
            KycCase.STATUS_TENANT_PENDING, req.subType(), req.legalName(), req.registrationNumber());
        c.makerName = makerName;
        c.submittedAt = Instant.now();
        c.dbaName = req.dbaName();
        c.entityType = req.entityType();
        c.registrationNumber = NamibiaRules.normalizeRegistration(req.registrationNumber());
        c.incorporationDate = req.incorporationDate();
        c.taxId = req.taxId();
        c.mccCode = req.mccCode();
        c.websiteUrl = req.websiteUrl();
        c.expectedMonthlyVolume = KycDtos.minor(req.expectedMonthlyVolume(), "expectedMonthlyVolume");
        c.avgTicketSize = KycDtos.minor(req.avgTicketSize(), "avgTicketSize");
        c.registeredAddress = req.registeredAddress();
        c.registeredCity = req.registeredCity();
        c.registeredRegion = req.registeredRegion();
        c.registeredPostal = req.registeredPostal();
        c.registeredCountry = req.registeredCountry();
        c.operationalAddressSame = req.operationalAddressSame() != null && req.operationalAddressSame();
        c.operationalAddress = req.operationalAddress();
        c.operationalCity = req.operationalCity();
        c.operationalRegion = req.operationalRegion();
        c.operationalPostal = req.operationalPostal();
        c.makerPersonId = makerPersonId;
        caseRepository.save(c);

        saveUbos(c, req);
        saveBankAccounts(c, req, makerPersonId);

        event(c, "CREATED", makerName, null);
        recordIdempotency(makerPersonId, idempotencyKey, 201, "application/json", "{}".getBytes());

        log.info("kyc_case_created caseId={} caseNo={} ownerTenant={} maker={}",
            c.id, caseNo, ownerTenantId, makerPersonId);
        return c;
    }

    @Transactional
    public KycCase update(UUID caseId, UUID ownerTenantId,
                          KycDtos.UpdateCaseRequest req, String idempotencyKey, UUID personId) {
        Long expectedVersion = req.expectedVersion();
        KycCase c = caseRepository.findByIdAndOwnerTenantId(caseId, ownerTenantId)
            .orElseThrow(() -> new ApiProblemException("KYC_CASE_NOT_FOUND", "Case not found", 404));

        if (!KycCase.STATUS_DRAFT.equals(c.status) && !KycCase.STATUS_REQUEST_INFO.equals(c.status)) {
            throw new ApiProblemException("KYC_STATE", "Only DRAFT or REQUEST_INFO cases can be updated", 409);
        }

        if (expectedVersion != null && c.version != expectedVersion) {
            throw new ApiProblemException("STALE_WRITE", "Case version mismatch — concurrent update", 409);
        }

        if (req.legalName() != null) c.legalName = req.legalName();
        if (req.dbaName() != null) c.dbaName = req.dbaName();
        if (req.entityType() != null) c.entityType = req.entityType();
        if (req.registrationNumber() != null) {
            c.registrationNumber = NamibiaRules.normalizeRegistration(req.registrationNumber());
        }
        if (req.incorporationDate() != null) c.incorporationDate = req.incorporationDate();
        if (req.taxId() != null) c.taxId = req.taxId();
        if (req.mccCode() != null) c.mccCode = req.mccCode();
        if (req.websiteUrl() != null) c.websiteUrl = req.websiteUrl();
        if (req.expectedMonthlyVolume() != null) c.expectedMonthlyVolume = KycDtos.minor(req.expectedMonthlyVolume(), "expectedMonthlyVolume");
        if (req.avgTicketSize() != null) c.avgTicketSize = KycDtos.minor(req.avgTicketSize(), "avgTicketSize");
        if (req.registeredAddress() != null) c.registeredAddress = req.registeredAddress();
        if (req.registeredCity() != null) c.registeredCity = req.registeredCity();
        if (req.registeredRegion() != null) c.registeredRegion = req.registeredRegion();
        if (req.registeredPostal() != null) c.registeredPostal = req.registeredPostal();
        if (req.registeredCountry() != null) c.registeredCountry = req.registeredCountry();
        if (req.operationalAddressSame() != null) c.operationalAddressSame = req.operationalAddressSame();
        if (req.operationalAddress() != null) c.operationalAddress = req.operationalAddress();
        if (req.operationalCity() != null) c.operationalCity = req.operationalCity();
        if (req.operationalRegion() != null) c.operationalRegion = req.operationalRegion();
        if (req.operationalPostal() != null) c.operationalPostal = req.operationalPostal();
        c.updatedAt = Instant.now();
        caseRepository.save(c);

        // masked numbers come back as null = "unchanged": carry the stored value by position
        List<String> oldIds = uboRepository.findByCaseIdOrderByPersonNoAsc(c.id).stream().map(u -> u.idNumber).toList();
        List<String> oldAccounts = bankRepository.findByCaseIdOrderByCreatedAtAsc(c.id).stream().map(b -> b.accountNumber).toList();
        if (req.ubos() != null) {
            uboRepository.deleteByCaseId(c.id);
            saveUbosFromUpdate(c, req, oldIds);
        }
        if (req.bankAccounts() != null) {
            bankRepository.deleteByCaseId(c.id);
            saveBankAccountsFromUpdate(c, req, personId, oldAccounts);
        }

        recordEvent(c.id, ownerTenantId, "CASE_UPDATED", "{\"caseId\":\"" + c.id + "\"}");
        recordIdempotency(personId, idempotencyKey, 200, "application/json", "{}".getBytes());

        log.info("kyc_case_updated caseId={} version={}", c.id, c.version);
        return c;
    }

    @Transactional
    public KycCase submit(UUID caseId, UUID ownerTenantId, UUID personId, Long expectedVersion, String idempotencyKey) {
        KycCase c = caseRepository.findByIdAndOwnerTenantId(caseId, ownerTenantId)
            .orElseThrow(() -> new ApiProblemException("KYC_CASE_NOT_FOUND", "Case not found", 404));

        if (!KycCase.STATUS_DRAFT.equals(c.status) && !KycCase.STATUS_REQUEST_INFO.equals(c.status)) {
            throw new ApiProblemException("KYC_STATE", "Only DRAFT or REQUEST_INFO cases can be submitted", 409);
        }
        if (expectedVersion != null && c.version != expectedVersion) {
            throw new ApiProblemException("STALE_WRITE", "Case version mismatch — concurrent update", 409);
        }

        c.status = KycCase.STATUS_SUBMITTED;
        c.updatedAt = Instant.now();
        caseRepository.save(c);

        recordEvent(c.id, ownerTenantId, "CASE_SUBMITTED", "{\"caseId\":\"" + c.id + "\"}");
        recordIdempotency(personId, idempotencyKey, 200, "application/json", "{}".getBytes());

        log.info("kyc_case_submitted caseId={} ownerTenant={}", c.id, ownerTenantId);
        return c;
    }

    // ---------- private helpers ----------

    private void validateCreate(KycDtos.CreateCaseRequest req) {
        // screening is the provider's verdict (ScreeningService), never the applicant's/maker's claim
        if (req.screeningOutcome() != null) {
            log.warn("kyc_screening_outcome_asserted_by_client refused");
            throw new ApiProblemException("SCREENING_NOT_CLIENT_SETTABLE",
                "screeningOutcome is decided by the screening provider and cannot be supplied", 400);
        }
        NamibiaRules.requireNonBlank(req.legalName(), "KYC_LEGAL_NAME", "legalName is required");
        NamibiaRules.requireNonBlank(req.registrationNumber(), "KYC_REGISTRATION", "registrationNumber is required");
        NamibiaRules.requireBipaRegistration(req.registrationNumber());
        if (req.taxId() != null) NamibiaRules.requireTin(req.taxId());
        NamibiaRules.requireNonBlank(req.registeredAddress(), "KYC_ADDRESS", "registeredAddress is required");
        NamibiaRules.requireNonBlank(req.registeredCity(), "KYC_ADDRESS", "registeredCity is required");
        NamibiaRules.requireNonBlank(req.registeredCountry(), "KYC_ADDRESS", "registeredCountry is required");
        if (req.ubos() == null || req.ubos().isEmpty()) {
            throw new ApiProblemException("KYC_UBOS_REQUIRED", "At least one beneficial owner is required", 400);
        }
        NamibiaRules.requireUboThreshold(
            req.ubos().stream().map(KycDtos.UboRequest::ownershipPct).toList());
        if (req.bankAccounts() != null) {
            req.bankAccounts().forEach(b -> NamibiaRules.requireBranchCode(b.branchCode()));
        }
    }

    private void saveUbos(KycCase c, KycDtos.CreateCaseRequest req) {
        int personNo = 1;
        for (KycDtos.UboRequest u : req.ubos()) {
            KycUbo row = new KycUbo();
            row.id = UUID.randomUUID();
            row.caseId = c.id;
            row.ownerTenantId = c.ownerTenantId;
            row.personNo = personNo++;
            row.fullName = u.fullName();
            row.dob = u.dob();
            row.nationality = u.nationality();
            row.residentialAddress = u.residentialAddress();
            row.phone = u.phone();
            row.email = u.email();
            row.idDocType = (u.idDocType() == null || u.idDocType().isBlank()) ? "NATIONAL_ID" : u.idDocType();
            row.idNumber = u.idNumber();
            row.idExpiry = u.idExpiry();
            row.ownershipPct = u.ownershipPct();
            row.isPep = Boolean.TRUE.equals(u.isPep());
            uboRepository.save(row);
        }
    }

    private void saveUbosFromUpdate(KycCase c, KycDtos.UpdateCaseRequest req, List<String> oldIds) {
        int personNo = 1;
        for (KycDtos.UboRequest u : req.ubos()) {
            KycUbo row = new KycUbo();
            row.id = UUID.randomUUID();
            row.caseId = c.id;
            row.ownerTenantId = c.ownerTenantId;
            row.personNo = personNo++;
            row.fullName = u.fullName();
            row.dob = u.dob();
            row.nationality = u.nationality();
            row.residentialAddress = u.residentialAddress();
            row.phone = u.phone();
            row.email = u.email();
            row.idDocType = (u.idDocType() == null || u.idDocType().isBlank()) ? "NATIONAL_ID" : u.idDocType();
            row.idNumber = u.idNumber() != null ? u.idNumber()
                : personNo - 2 < oldIds.size() ? oldIds.get(personNo - 2) : null;
            row.idExpiry = u.idExpiry();
            row.ownershipPct = u.ownershipPct();
            row.isPep = Boolean.TRUE.equals(u.isPep());
            uboRepository.save(row);
        }
    }

    private void saveBankAccounts(KycCase c, KycDtos.CreateCaseRequest req, UUID requestedBy) {
        for (KycDtos.BankAccountRequest b : req.bankAccounts()) {
            KycBankAccount row = new KycBankAccount();
            row.id = UUID.randomUUID();
            row.caseId = c.id;
            row.ownerTenantId = c.ownerTenantId;
            row.holderName = (b.holderName() == null || b.holderName().isBlank()) ? c.legalName : b.holderName();
            row.bankName = b.bankName();
            row.branchCode = b.branchCode().trim();
            row.accountNumber = b.accountNumber();
            row.accountType = b.accountType() == null ? "SETTLEMENT" : b.accountType();
            row.status = KycBankAccount.STATUS_PENDING_APPROVAL;
            row.requestedBy = requestedBy;
            bankRepository.save(row);
        }
    }

    private void saveBankAccountsFromUpdate(KycCase c, KycDtos.UpdateCaseRequest req, UUID requestedBy,
                                            List<String> oldAccounts) {
        int n = 0;
        for (KycDtos.BankAccountRequest b : req.bankAccounts()) {
            String previous = n < oldAccounts.size() ? oldAccounts.get(n) : null;
            n++;
            KycBankAccount row = new KycBankAccount();
            row.id = UUID.randomUUID();
            row.caseId = c.id;
            row.ownerTenantId = c.ownerTenantId;
            row.holderName = (b.holderName() == null || b.holderName().isBlank()) ? c.legalName : b.holderName();
            row.bankName = b.bankName();
            row.branchCode = b.branchCode().trim();
            row.accountNumber = b.accountNumber() != null ? b.accountNumber() : previous;
            row.accountType = b.accountType() == null ? "SETTLEMENT" : b.accountType();
            row.status = KycBankAccount.STATUS_PENDING_APPROVAL;
            row.requestedBy = requestedBy;
            bankRepository.save(row);
        }
    }

    /** Record a domain event with a display actor + note (rendered by the widget's History tab). */
    @Transactional
    public void event(KycCase c, String type, String actorName, String note) {
        try {
            recordEvent(c.id, c.ownerTenantId, type, new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of("actorName", actorName == null ? "" : actorName, "note", note == null ? "" : note)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void recordEvent(UUID caseId, UUID ownerTenantId, String eventType, String payload) {
        KycCaseEvent e = new KycCaseEvent();
        e.id = UUID.randomUUID();
        e.caseId = caseId;
        e.ownerTenantId = ownerTenantId;
        e.eventType = eventType;
        e.payload = payload;
        eventRepository.save(e);
    }

    private void recordIdempotency(UUID personId, String key, int status, String contentType, byte[] body) {
        var existing = idempotencyRepository.findByPersonIdAndRequestKey(personId, key);
        if (existing.isPresent()) return;
        IdempotencyRecord r = new IdempotencyRecord();
        r.id = UUID.randomUUID();
        r.personId = personId;
        r.requestKey = key;
        r.status = "DONE";
        r.responseStatus = status;
        r.responseContentType = contentType;
        r.responseBody = body;
        r.createdAt = Instant.now();
        r.updatedAt = Instant.now();
        idempotencyRepository.save(r);
    }

    private KycDtos.CaseSummary toSummary(KycCase c) {
        return new KycDtos.CaseSummary(
            c.id, c.caseNo, c.subjectTenantId, c.legalName, c.dbaName,
            c.subType, c.registrationNumber, c.status, c.riskRating,
            c.submittedAt, c.nextReviewDate, c.makerName, c.updatedAt, c.version, isStalled(c),
            c.caseKind, c.primaryContact,
            KycCase.KIND_CUSTOMER.equals(c.caseKind) ? c.contactVerifiedAt != null : null
        );
    }

    static KycDtos.CustomerView customerView(KycCase c) {
        if (!KycCase.KIND_CUSTOMER.equals(c.caseKind)) return null;
        return new KycDtos.CustomerView(c.subjectPersonId, c.firstName, c.lastName, c.gender, c.dateOfBirth,
            maskEmail(c.email), mask(c.phone), c.primaryContact, c.contactVerifiedAt != null,
            c.contactVerifiedAt, c.activationSentAt);
    }

    /** j•••@example.com — enough for staff to recognise, not to harvest. */
    static String maskEmail(String e) {
        if (e == null) return null;
        int at = e.indexOf('@');
        if (at <= 1) return e;
        return e.charAt(0) + "•••" + e.substring(at);
    }

    /** PII is masked to the last 4 on every read (no PII-read permission exists in core yet). */
    static String mask(String v) {
        if (v == null || v.length() <= 4) return v;
        return "•••• " + v.substring(v.length() - 4);
    }

    /** Saga stuck > 10 min (plan §12.5) — operator-visible. */
    static boolean isStalled(KycCase c) {
        return KycCase.STATUS_STALLED.equals(c.status)
            || ((KycCase.STATUS_TENANT_PENDING.equals(c.status) || KycCase.STATUS_SUBMITTED.equals(c.status))
                && c.updatedAt.isBefore(Instant.now().minusSeconds(600)));
    }

    /**
     * Whether the acting person is a designated checker. Core owns the flag (persons.is_checker) —
     * the plugin never reads core tables (AGENTS §4), so it asks core as that person
     * (GET /v1/me/permissions → "checker"). Core re-checks the flag when the decision resolves the
     * approval, so an unreachable core here only hides the decision controls; it never grants them.
     */
    public boolean isChecker(String actorAssertion) {
        if (actorAssertion == null || actorAssertion.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(core.me(actorAssertion).get("checker"));
        } catch (RuntimeException e) {
            log.warn("kyc_checker_lookup_failed error={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
