package com.fsp.plugin.kyc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fsp.plugin.kyc.api.KycDtos;
import com.fsp.plugin.kyc.domain.KycCheck;
import com.fsp.plugin.kyc.repository.KycCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Screening is the provider's verdict from the application — never the caller's claim. */
class ScreeningServiceTest {

    private KycCheckRepository repo;
    private ScreeningService svc;
    private final UUID caseId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(KycCheckRepository.class);
        svc = new ScreeningService(repo, List.of("Sanctioned Test Entity", "SANCTIONED TEST PERSON"));
    }

    @Test
    void cleanApplicationPassesAndIsPersisted() {
        assertThat(svc.runIdentity(caseId, owner, List.of(ubo("Elsi Hove", false))).result()).isEqualTo(KycCheck.RESULT_PASS);
        assertThat(svc.runSanctions(caseId, owner, "Sunrise (Pty) Ltd", List.of(ubo("Elsi Hove", false))).result())
            .isEqualTo(KycCheck.RESULT_PASS);
        verify(repo, times(2)).save(any(KycCheck.class));
    }

    @Test
    void pepNeedsManualReview() {
        assertThat(svc.runSanctions(caseId, owner, "Sunrise (Pty) Ltd", List.of(ubo("Elsi Hove", true))).result())
            .isEqualTo(KycCheck.RESULT_MANUAL_REVIEW);
    }

    @Test
    void watchListHitFailsCaseInsensitively() {
        assertThat(svc.runSanctions(caseId, owner, "  sanctioned   test entity ", List.of(ubo("Elsi Hove", false))).result())
            .isEqualTo(KycCheck.RESULT_FAIL);
        assertThat(svc.runSanctions(caseId, owner, "Ok Ltd", List.of(ubo("Sanctioned Test Person", false))).result())
            .isEqualTo(KycCheck.RESULT_FAIL);
    }

    @Test
    void incompleteOrExpiredIdentityNeedsManualReview() {
        KycDtos.UboRequest expired = new KycDtos.UboRequest("Elsi Hove", LocalDate.of(1980, 2, 14), "NAM", "x", null, null,
            "ID", "80021400123", LocalDate.now().minusDays(1), BigDecimal.valueOf(100), false);
        var check = svc.runIdentity(caseId, owner, List.of(expired));
        assertThat(check.result()).isEqualTo(KycCheck.RESULT_MANUAL_REVIEW);
        assertThat(check.details()).contains("valid ID document");
        assertThat(svc.runIdentity(caseId, owner, List.of()).result()).isEqualTo(KycCheck.RESULT_MANUAL_REVIEW);
    }

    private static KycDtos.UboRequest ubo(String name, boolean pep) {
        return new KycDtos.UboRequest(name, LocalDate.of(1980, 2, 14), "NAM", "12 Fidel Castro St", "+264", "e@x.na",
            "ID", "80021400123", LocalDate.now().plusYears(3), BigDecimal.valueOf(100), pep);
    }
}
