package com.fsp.plugin.kyc.service;

import com.fsp.plugin.kyc.api.KycDtos;
import com.fsp.plugin.kyc.domain.KycCase;
import com.fsp.plugin.kyc.repository.KycCaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Organizations API (§12.7) — joins core's subtree (tenant IDs) with the plugin's latest
 * case per subject tenant.
 */
@Slf4j
@Service
public class KycOrganizationService {

    private final CoreClient coreClient;
    private final KycCaseRepository caseRepository;

    public KycOrganizationService(CoreClient coreClient, KycCaseRepository caseRepository) {
        this.coreClient = coreClient;
        this.caseRepository = caseRepository;
    }

    /**
     * kyc.org.get — one organization's KYC standing. Core decides visibility (the person's chain,
     * via the actor assertion); the plugin adds the latest case it holds for that subject.
     */
    @Transactional(readOnly = true)
    public KycDtos.OrganizationRow get(UUID tenantId, String actorAssertion) {
        Map<String, Object> t = coreClient.getTenant(tenantId, actorAssertion);
        KycCase latest = caseRepository.findBySubjectTenantId(tenantId).stream()
            .max(java.util.Comparator.comparing((KycCase c) -> c.updatedAt))
            .orElse(null);
        Object parent = t.get("parentTenantId");
        return new KycDtos.OrganizationRow(
            tenantId,
            t.get("legalName") == null ? null : String.valueOf(t.get("legalName")),
            t.get("subType") == null ? null : String.valueOf(t.get("subType")),
            String.valueOf(t.getOrDefault("status", "")),
            String.valueOf(t.getOrDefault("verificationStatus", "")),
            Integer.valueOf(String.valueOf(t.getOrDefault("depth", "0"))),
            parent == null ? null : UUID.fromString(String.valueOf(parent)),
            latest != null ? latest.id : null,
            latest != null ? latest.status : null,
            latest != null ? latest.riskRating : null,
            latest != null ? latest.nextReviewDate : null);
    }

    @Transactional(readOnly = true)
    public KycDtos.OrganizationListResponse list(UUID ownerTenantId, int page, int size,
                                                  String q, String status, String sort,
                                                  String actorAssertion) {
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("PAGE_SIZE_INVALID: size must be 1-100");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> subtree = coreClient.getSubtree(ownerTenantId, page, size, sort, actorAssertion);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coreItems = (List<Map<String, Object>>) subtree.getOrDefault("items", List.of());

        List<UUID> subjectIds = coreItems.stream()
            .map(i -> UUID.fromString(String.valueOf(i.get("id"))))
            .toList();

        List<KycCase> allCases = subjectIds.stream()
            .flatMap(id -> caseRepository.findBySubjectTenantId(id).stream())
            .collect(Collectors.toList());
        Map<UUID, KycCase> latestCasePerSubject = new LinkedHashMap<>();
        for (KycCase c : allCases) {
            KycCase existing = latestCasePerSubject.get(c.subjectTenantId);
            if (existing == null || c.updatedAt.isAfter(existing.updatedAt)) {
                latestCasePerSubject.put(c.subjectTenantId, c);
            }
        }

        List<KycDtos.OrganizationRow> rows = coreItems.stream().map(coreItem -> {
            UUID tenantId = UUID.fromString(String.valueOf(coreItem.get("id")));
            KycCase kycCase = latestCasePerSubject.get(tenantId);
            return new KycDtos.OrganizationRow(
                tenantId,
                String.valueOf(coreItem.getOrDefault("legalName", "")),
                String.valueOf(coreItem.getOrDefault("subType", "")),
                String.valueOf(coreItem.getOrDefault("status", "")),
                String.valueOf(coreItem.getOrDefault("verificationStatus", "")),
                Integer.valueOf(String.valueOf(coreItem.getOrDefault("depth", "0"))),
                UUID.fromString(String.valueOf(coreItem.getOrDefault("parentTenantId", "00000000-0000-0000-0000-000000000000"))),
                kycCase != null ? kycCase.id : null,
                kycCase != null ? kycCase.status : null,
                kycCase != null ? kycCase.riskRating : null,
                kycCase != null ? kycCase.nextReviewDate : null
            );
        }).toList();

        boolean hasNext = (Boolean) subtree.getOrDefault("hasNext", false);
        Long nextAfter = subtree.get("nextAfter") != null ? Long.valueOf(subtree.get("nextAfter").toString()) : null;

        return new KycDtos.OrganizationListResponse(rows, page, size, hasNext, nextAfter);
    }
}
