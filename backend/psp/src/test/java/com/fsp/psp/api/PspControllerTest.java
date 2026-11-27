package com.fsp.psp.api;

import com.fsp.psp.acquire.AcquireService;
import com.fsp.psp.acquire.Acquisition;
import com.fsp.psp.acquire.AcquisitionRepository;
import com.fsp.psp.common.ApiException;
import com.fsp.psp.merchant.Merchant;
import com.fsp.psp.merchant.MerchantRepository;
import com.fsp.psp.merchant.MerchantService;
import com.fsp.psp.settlement.SettlementService;
import com.fsp.psp.statement.StatementService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage-5 wire tests: the zero-trust edge rejects untrusted hops and machine actors; the
 * PSP surface is tenant-scoped (foreign ids answer 404, never 403) and money moves only
 * with an Idempotency-Key (§2e), amounts as minor-unit strings (§2b).
 */
class PspControllerTest {

    static final String CREDS = "test-internal-credential";

    final UUID tenantId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    final UUID merchantId = UUID.randomUUID();
    final UUID acqId = UUID.randomUUID();

    MerchantRepository merchantRepo = Mockito.mock(MerchantRepository.class);
    MerchantService merchantService = Mockito.mock(MerchantService.class);
    AcquireService acquireService = Mockito.mock(AcquireService.class);
    AcquisitionRepository acquisitionRepo = Mockito.mock(AcquisitionRepository.class);
    SettlementService settlementService = Mockito.mock(SettlementService.class);
    StatementService statementService = Mockito.mock(StatementService.class);

    MerchantController merchantController = new MerchantController(merchantService);
    AcquireController acquireController = new AcquireController(acquireService, merchantService, acquisitionRepo);
    com.fsp.psp.settlement.MerchantPayableRepository payables =
        Mockito.mock(com.fsp.psp.settlement.MerchantPayableRepository.class);
    PspSettlementController settlementController =
        new PspSettlementController(settlementService, statementService, payables, merchantService);
    PspIdentityFilter filter = new PspIdentityFilter(CREDS);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(merchantController, acquireController,
                settlementController)
            .addFilters(filter)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    private java.util.Map<String, Object> merchantView() {
        Merchant m = ownMerchant();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", m.id.toString());
        out.put("merchantCode", m.merchantCode);
        out.put("displayName", m.displayName);
        out.put("status", m.status);
        out.put("payOverDays", m.payOverDays);
        return out;
    }

    private Merchant ownMerchant() {
        Merchant m = new Merchant();
        m.id = merchantId;
        m.tenantId = tenantId;
        m.merchantCode = "acme";
        m.displayName = "Acme Traders";
        m.status = Merchant.STATUS_ACTIVE;
        m.payOverDays = 1;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        return m;
    }

    private Acquisition ownAcq(String state) {
        Acquisition a = new Acquisition();
        a.id = acqId;
        a.merchantId = merchantId;
        a.tenantId = tenantId;
        a.connectorCode = "DEMO-BANK";
        a.currency = "NAD";
        a.grossMinor = new BigDecimal(50000);
        a.feeMinor = new BigDecimal(500);
        a.netMinor = new BigDecimal(49500);
        a.pspRef = "ORD-1";
        a.state = state;
        a.createdAt = Instant.now();
        a.updatedAt = a.createdAt;
        return a;
    }

    // ------------------------------------------------------------------ zero-trust edge

    @Test
    void missingCredentialHeadersRejected() throws Exception {
        mvc.perform(get("/v1/psp/merchants")).andExpect(status().isUnauthorized());
    }

    @Test
    void machineActorRejected() throws Exception {
        mvc.perform(get("/v1/psp/merchants")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", "client:svc-batch")
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "svc-batch")
                .header("X-FSP-Client-Id", "svc-core"))
            .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ happy paths

    @Test
    void onboardAndAcquireHappyPath() throws Exception {
        when(merchantService.onboard(eq(tenantId), eq("acme"), eq("Acme Traders"),
                eq("NB-ACC-1"), eq(1))).thenReturn(ownMerchant());
        when(merchantService.onboard(Mockito.any(), Mockito.anyString(), Mockito.anyString(),
                Mockito.any(), Mockito.any())).thenReturn(ownMerchant());
        mvc.perform(post("/v1/psp/merchants")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", "11111111-2222-4333-8444-555555555555")
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web-spa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"merchantCode":"acme","displayName":"Acme Traders",
                     "merchantBankAccountRef":"NB-ACC-1","payOverDays":1}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.merchantCode").value("acme"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.payOverDays").value(1));

        when(acquireService.authorize(eq(tenantId), eq(merchantId), eq("DEMO-BANK"), eq("ORD-1"),
                eq(50000L), eq(500L), eq("NAD"), eq("idem-1")))
            .thenReturn(new AcquireService.AuthorizeResult(ownAcq("AUTHORISED"), false));
        mvc.perform(post("/v1/psp/merchants/" + merchantId + "/acquisitions")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", "11111111-2222-4333-8444-555555555555")
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web-spa")
                .header("Idempotency-Key", "idem-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"connectorCode":"DEMO-BANK","pspRef":"ORD-1",
                     "grossMinor":"50000","feeMinor":"500","currency":"NAD"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.grossMinor").value("50000")) // money as STRING §2b
            .andExpect(jsonPath("$.state").value("AUTHORISED"));
    }

    @Test
    void acquireWithoutIdempotencyKeyRejected() throws Exception {
        Mockito.doThrow(new ApiException(HttpStatus.BAD_REQUEST, "PSP_IDEMPOTENCY_REQUIRED",
                "acquire requires an Idempotency-Key header"))
            .when(acquireService).authorize(Mockito.any(), Mockito.any(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.isNull());
        mvc.perform(post("/v1/psp/merchants/" + merchantId + "/acquisitions")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", "11111111-2222-4333-8444-555555555555")
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web-spa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"connectorCode":"DEMO-BANK","pspRef":"ORD-1",
                     "grossMinor":"50000","feeMinor":"500","currency":"NAD"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PSP_IDEMPOTENCY_REQUIRED"));
    }

    // ------------------------------------------------------------------ tenant scoping

    @Test
    void foreignIdsAnswer404Never403() throws Exception {
        UUID other = UUID.randomUUID();
        Mockito.doThrow(new ApiException(HttpStatus.NOT_FOUND, "PSP_ACQ_NOT_FOUND",
                "acquisition not found"))
            .when(acquireService).owned(tenantId, other);
        mvc.perform(get("/v1/psp/acquisitions/" + other)
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", "11111111-2222-4333-8444-555555555555")
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web-spa"))
            .andExpect(status().isNotFound());
    }

    @Test
    void listEnforcesSortWhitelist() throws Exception {
        Mockito.doThrow(new ApiException(HttpStatus.BAD_REQUEST, "PSP_SORT_INVALID",
                "sort field not permitted"))
            .when(merchantService).list(Mockito.any(), Mockito.eq(0), Mockito.eq(20),
                Mockito.eq("tenantId,asc"), Mockito.isNull());
        mvc.perform(get("/v1/psp/merchants?sort=tenantId,asc")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", "11111111-2222-4333-8444-555555555555")
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web-spa"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void historyListsServerSidePaged() throws Exception {
        when(acquireService.history(eq(tenantId), eq(merchantId), Mockito.anyInt(),
                Mockito.anyInt(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
            .thenReturn(new PageImpl<>(List.of(ownAcq("AUTHORISED"))));
        mvc.perform(get("/v1/psp/merchants/" + merchantId + "/acquisitions")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", "11111111-2222-4333-8444-555555555555")
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web-spa"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acquisitions[0].pspRef").value("ORD-1"));
    }

    // ------------------------------------------------------------------ error mapping

    @Test
    void conflictErrorsMapTo409() throws Exception {
        when(acquireService.authorize(eq(tenantId), eq(merchantId), eq("DEMO-BANK"), eq("ORD-DUP"),
                eq(1L), eq(0L), eq("NAD"), eq("idem-dup")))
            .thenThrow(new ApiException(HttpStatus.CONFLICT, "PSP_REF_TAKEN", "dup ref"));
        mvc.perform(post("/v1/psp/merchants/" + merchantId + "/acquisitions")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", "11111111-2222-4333-8444-555555555555")
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web-spa")
                .header("Idempotency-Key", "idem-dup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"connectorCode":"DEMO-BANK","pspRef":"ORD-DUP",
                     "grossMinor":"1","feeMinor":"0","currency":"NAD"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PSP_REF_TAKEN"));
    }
}
