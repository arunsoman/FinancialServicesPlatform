package com.fsp.plugin.kyc.service;

import com.fsp.plugin.kyc.api.KycDtos;
import com.fsp.plugin.kyc.domain.KycCheck;
import com.fsp.plugin.kyc.repository.KycCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Screening provider port (Phase-0: deterministic SANDBOX rules). The outcome is decided HERE from
 * the application data — never taken from the request: a maker can no longer assert their own
 * applicant's screening result. Every check is persisted (kyc_checks) so the checker's
 * Screening tab shows what was found. Real providers replace {@link #evaluate} behind this API.
 *
 * <p>Sandbox rules:
 * <ul>
 *   <li>IDENTITY — PASS when every UBO has a name, date of birth, nationality, ID number and an
 *       unexpired ID; otherwise MANUAL_REVIEW (listing what is missing).</li>
 *   <li>SANCTIONS — FAIL when the legal name or a UBO name is on the sandbox watch-list
 *       ({@code fsp.kyc.screening.sandbox-watchlist}); MANUAL_REVIEW when any UBO is a PEP;
 *       otherwise PASS.</li>
 * </ul>
 */
@Slf4j
@Service
public class ScreeningService {

    public record Check(UUID id, UUID caseId, UUID ownerTenantId, String checkType, String result, String details) {}

    private final KycCheckRepository checks;
    private final Set<String> watchlist;

    public ScreeningService(KycCheckRepository checks,
                            @Value("${fsp.kyc.screening.sandbox-watchlist:SANCTIONED TEST ENTITY,SANCTIONED TEST PERSON}")
                            List<String> watchlist) {
        this.checks = checks;
        this.watchlist = watchlist.stream().map(ScreeningService::norm).filter(s -> !s.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    public Check runIdentity(UUID caseId, UUID ownerTenantId, List<KycDtos.UboRequest> ubos) {
        List<String> gaps = new ArrayList<>();
        List<KycDtos.UboRequest> list = ubos == null ? List.of() : ubos;
        if (list.isEmpty()) gaps.add("no beneficial owners declared");
        LocalDate today = LocalDate.now();
        for (int i = 0; i < list.size(); i++) {
            KycDtos.UboRequest u = list.get(i);
            String who = "owner " + (i + 1);
            if (blank(u.fullName())) gaps.add(who + ": name");
            if (u.dob() == null) gaps.add(who + ": date of birth");
            if (blank(u.nationality())) gaps.add(who + ": nationality");
            if (blank(u.idNumber())) gaps.add(who + ": ID number");
            if (u.idExpiry() == null || !u.idExpiry().isAfter(today)) gaps.add(who + ": valid ID document");
        }
        String result = gaps.isEmpty() ? KycCheck.RESULT_PASS : KycCheck.RESULT_MANUAL_REVIEW;
        String details = gaps.isEmpty()
            ? "Identity data complete for %d owner(s) (sandbox provider)".formatted(list.size())
            : "Identity data incomplete: " + String.join(", ", gaps) + " (sandbox provider)";
        return persist(new Check(UUID.randomUUID(), caseId, ownerTenantId, KycCheck.CHECK_IDENTITY, result, details));
    }

    public Check runSanctions(UUID caseId, UUID ownerTenantId, String legalName, List<KycDtos.UboRequest> ubos) {
        List<KycDtos.UboRequest> list = ubos == null ? List.of() : ubos;
        List<String> hits = new ArrayList<>();
        if (watchlist.contains(norm(legalName))) hits.add("organization");
        for (int i = 0; i < list.size(); i++) {
            if (watchlist.contains(norm(list.get(i).fullName()))) hits.add("owner " + (i + 1));
        }
        long peps = list.stream().filter(u -> Boolean.TRUE.equals(u.isPep())).count();
        String result;
        String details;
        if (!hits.isEmpty()) {
            result = KycCheck.RESULT_FAIL;
            details = "Watch-list match: " + String.join(", ", hits) + " (sandbox provider)";
        } else if (peps > 0) {
            result = KycCheck.RESULT_MANUAL_REVIEW;
            details = "%d politically exposed owner(s) — enhanced due diligence required (sandbox provider)".formatted(peps);
        } else {
            result = KycCheck.RESULT_PASS;
            details = "No sanctions or PEP matches (sandbox provider)";
        }
        return persist(new Check(UUID.randomUUID(), caseId, ownerTenantId, KycCheck.CHECK_SANCTIONS, result, details));
    }

    private Check persist(Check check) {
        checks.save(new KycCheck(check.id(), check.caseId(), check.ownerTenantId(), check.checkType(),
            check.result(), check.details()));
        switch (check.result()) {
            case KycCheck.RESULT_FAIL, KycCheck.RESULT_MANUAL_REVIEW -> log.warn(
                "kyc_screening_negative caseId={} type={} result={}", check.caseId(), check.checkType(), check.result());
            default -> log.info("kyc_screening_clean caseId={} type={}", check.caseId(), check.checkType());
        }
        return check;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
