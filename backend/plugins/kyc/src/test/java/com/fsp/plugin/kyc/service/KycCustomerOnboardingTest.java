package com.fsp.plugin.kyc.service;

import com.fsp.plugin.kyc.api.ApiProblemException;
import com.fsp.plugin.kyc.api.KycDtos;
import com.fsp.plugin.kyc.domain.IdempotencyRecord;
import com.fsp.plugin.kyc.domain.KycCase;
import com.fsp.plugin.kyc.repository.IdempotencyRecordRepository;
import com.fsp.plugin.kyc.repository.KycCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KycCustomerOnboardingTest {

    @Test
    void definiteCoreRejectionClosesCaseReleasesContactAndReplaysStoredProblem() {
        Fixture f = new Fixture();
        String key = "customer-registration-1";
        ApiProblemException coreError = new ApiProblemException("CUSTOMER_CONTACT_TAKEN",
            "Email is already registered.", 409);
        UUID personId = UUID.randomUUID();
        when(f.core.createCustomer(eq(f.owner), any(Map.class), eq("actor-assertion"), any(String.class)))
            .thenThrow(coreError)
            .thenReturn(Map.of("personId", personId.toString()));
        when(f.core.openApproval(any(Map.class), eq("actor-assertion"), any(String.class)))
            .thenReturn(Map.of("id", UUID.randomUUID().toString()));

        assertThatThrownBy(() -> f.service.onboard(f.owner, f.maker, "maker", request(), "actor-assertion", key))
            .isSameAs(coreError);

        KycCase failedCase = f.casesById.values().stream().filter(c -> KycCase.KIND_CUSTOMER.equals(c.caseKind))
            .findFirst().orElseThrow();
        assertThat(failedCase.status).isEqualTo(KycCase.STATUS_REJECTED);
        verify(f.caseService).event(eq(failedCase), eq("CORE_REJECTED"), eq("KYC plugin"),
            org.mockito.ArgumentMatchers.contains("CUSTOMER_CONTACT_TAKEN"));

        IdempotencyRecord record = f.idempotencyByKey.get(key);
        assertThat(record).isNotNull();
        assertThat(record.status).isEqualTo("DONE");
        assertThat(record.responseStatus).isEqualTo(409);
        assertThat(record.responseContentType).isEqualTo("application/problem+json");
        assertThat(new String(record.responseBody, StandardCharsets.UTF_8))
            .contains("CUSTOMER_CONTACT_TAKEN", "Email is already registered.");

        assertThatThrownBy(() -> f.service.onboard(f.owner, f.maker, "maker", request(), "actor-assertion", key))
            .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                assertThat(e.getCode()).isEqualTo(coreError.getCode());
                assertThat(e.getStatus()).isEqualTo(coreError.getStatus());
                assertThat(e.getMessage()).isEqualTo(coreError.getMessage());
            });
        verify(f.core).createCustomer(eq(f.owner), any(Map.class), eq("actor-assertion"), any(String.class));

        KycCase oldStalled = new KycCase(UUID.randomUUID(), f.owner, null, "KYC-OLD-STALLED",
            KycCase.STATUS_STALLED, null, "John Doe", null);
        oldStalled.caseKind = KycCase.KIND_CUSTOMER;
        oldStalled.email = "john@example.com";
        f.casesById.put(oldStalled.id, oldStalled);

        KycCase newCase = f.service.onboard(f.owner, f.maker, "maker", request(), "actor-assertion",
            "customer-registration-2");
        assertThat(newCase.status).isEqualTo(KycCase.STATUS_PENDING_REVIEW);
        verify(f.jdbc, org.mockito.Mockito.atLeastOnce()).queryForObject(
            org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("status NOT IN ('REJECTED', 'STALLED')")),
            eq(Boolean.class), eq(f.owner), eq("john@example.com"));
    }

    @Test
    void coreServerErrorLeavesTheProvisionalCaseAndIdempotencyRecordUntouched() {
        Fixture f = new Fixture();
        when(f.core.createCustomer(eq(f.owner), any(Map.class), eq("actor-assertion"), any(String.class)))
            .thenThrow(new ApiProblemException("CORE_UNAVAILABLE", "The platform could not be reached", 502));

        assertThatThrownBy(() -> f.service.onboard(f.owner, f.maker, "maker", request(), "actor-assertion",
            "customer-registration-502"))
            .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.getStatus()).isEqualTo(502));

        KycCase savedCase = f.casesById.values().iterator().next();
        assertThat(savedCase.status).isEqualTo(KycCase.STATUS_TENANT_PENDING);
        verify(f.caseService, never()).event(any(KycCase.class), eq("CORE_REJECTED"), anyString(), anyString());
        IdempotencyRecord record = f.idempotencyByKey.get("customer-registration-502");
        assertThat(record.responseStatus).isEqualTo(201);
        assertThat(new String(record.responseBody, StandardCharsets.UTF_8)).isEqualTo("{}");
    }

    @Test
    void unlistedCoreFourHundredErrorAlsoClosesTheCase() {
        Fixture f = new Fixture();
        when(f.core.createCustomer(eq(f.owner), any(Map.class), eq("actor-assertion"), any(String.class)))
            .thenThrow(new ApiProblemException("NEW_POLICY_DENIAL", "The platform refused the request", 403));

        assertThatThrownBy(() -> f.service.onboard(f.owner, f.maker, "maker", request(), "actor-assertion",
            "customer-registration-new-403"))
            .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                assertThat(e.getCode()).isEqualTo("NEW_POLICY_DENIAL");
                assertThat(e.getStatus()).isEqualTo(403);
            });

        KycCase savedCase = f.casesById.values().iterator().next();
        assertThat(savedCase.status).isEqualTo(KycCase.STATUS_REJECTED);
        assertThat(f.idempotencyByKey.get("customer-registration-new-403").responseStatus).isEqualTo(403);
        verify(f.caseService).event(eq(savedCase), eq("CORE_REJECTED"), eq("KYC plugin"),
            org.mockito.ArgumentMatchers.contains("NEW_POLICY_DENIAL"));
    }

    @Test
    void cleanupFailureIsSuppressedAndDoesNotReplaceCoreRejection() {
        Fixture f = new Fixture();
        AtomicInteger transactions = new AtomicInteger();
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup failed");
        doAnswer(invocation -> {
            if (transactions.getAndIncrement() == 0) return new SimpleTransactionStatus();
            throw cleanupFailure;
        }).when(f.transactionManager).getTransaction(any(TransactionDefinition.class));
        ApiProblemException coreError = new ApiProblemException("CUSTOMER_CONTACT_TAKEN",
            "Email is already registered.", 409);
        when(f.core.createCustomer(eq(f.owner), any(Map.class), eq("actor-assertion"), any(String.class)))
            .thenThrow(coreError);

        assertThatThrownBy(() -> f.service.onboard(f.owner, f.maker, "maker", request(), "actor-assertion",
            "customer-registration-cleanup"))
            .isSameAs(coreError);
        assertThat(coreError.getSuppressed()).containsExactly(cleanupFailure);
    }

    private static KycDtos.CreateCustomerRequest request() {
        return new KycDtos.CreateCustomerRequest("John", "Doe", "MALE", LocalDate.of(1990, 1, 1),
            null, "john@example.com", "EMAIL",
            new KycDtos.AddressRequest("12 Main Street", "Windhoek", "KHOMAS", null), true, null);
    }

    private static final class Fixture {
        private final UUID owner = UUID.randomUUID();
        private final UUID maker = UUID.randomUUID();
        private final Map<UUID, KycCase> casesById = new HashMap<>();
        private final Map<String, IdempotencyRecord> idempotencyByKey = new HashMap<>();
        private final KycCaseRepository cases = mock(KycCaseRepository.class);
        private final KycCaseService caseService = mock(KycCaseService.class);
        private final IdempotencyRecordRepository idempotency = mock(IdempotencyRecordRepository.class);
        private final ScreeningService screening = mock(ScreeningService.class);
        private final CoreClient core = mock(CoreClient.class);
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        private final KycCustomerService service;

        private Fixture() {
            when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
            when(idempotency.findByPersonIdAndRequestKey(eq(maker), any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(idempotencyByKey.get(invocation.getArgument(1))));
            when(idempotency.save(any(IdempotencyRecord.class))).thenAnswer(invocation -> {
                IdempotencyRecord record = invocation.getArgument(0);
                idempotencyByKey.put(record.requestKey, record);
                return record;
            });
            doAnswer(invocation -> {
                IdempotencyRecord record = invocation.getArgument(0);
                idempotencyByKey.remove(record.requestKey);
                return null;
            }).when(idempotency).delete(any(IdempotencyRecord.class));
            when(cases.saveAndFlush(any(KycCase.class))).thenAnswer(invocation -> {
                KycCase c = invocation.getArgument(0);
                casesById.put(c.id, c);
                return c;
            });
            when(cases.save(any(KycCase.class))).thenAnswer(invocation -> {
                KycCase c = invocation.getArgument(0);
                casesById.put(c.id, c);
                return c;
            });
            when(cases.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(casesById.get(invocation.getArgument(0))));
            doAnswer(invocation -> {
                casesById.remove(invocation.getArgument(0));
                return null;
            }).when(cases).deleteById(any(UUID.class));
            when(jdbc.queryForObject(any(String.class), eq(Boolean.class), eq(owner), eq("john@example.com")))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    boolean ignoresRejected = sql.contains("'REJECTED'");
                    boolean ignoresStalled = sql.contains("'STALLED'");
                    return casesById.values().stream().anyMatch(c ->
                        owner.equals(c.ownerTenantId)
                            && KycCase.KIND_CUSTOMER.equals(c.caseKind)
                            && "john@example.com".equals(c.email)
                            && !(ignoresRejected && KycCase.STATUS_REJECTED.equals(c.status))
                            && !(ignoresStalled && KycCase.STATUS_STALLED.equals(c.status)));
                });
            when(jdbc.queryForObject("SELECT nextval('kyc_case_no_seq')", Long.class)).thenReturn(1L);
            service = new KycCustomerService(cases, caseService, idempotency, screening, core, jdbc,
                new TransactionTemplate(transactionManager));
        }
    }
}