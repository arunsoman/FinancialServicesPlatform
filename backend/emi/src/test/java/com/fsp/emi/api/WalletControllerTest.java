package com.fsp.emi.api;

import com.fsp.emi.bill.BillPaymentSaga;
import com.fsp.emi.bill.BillPaymentSagaEngine;
import com.fsp.emi.bill.BillPaymentSagaRepository;
import com.fsp.emi.bill.Biller;
import com.fsp.emi.bill.BillerRepository;
import com.fsp.emi.charge.Charge;
import com.fsp.emi.charge.ChargeRepository;
import com.fsp.emi.charge.ChargeService;
import com.fsp.emi.common.ApiException;
import com.fsp.emi.wallet.EmiWalletService;
import com.fsp.emi.wallet.Wallet;
import com.fsp.emi.wallet.WalletJournal;
import com.fsp.emi.wallet.WalletRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.Instant;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Stage-3 wire tests: the zero-trust edge rejects untrusted hops and machine actors; the
 * self-service surface enforces ownership in-domain (foreign wallet ids answer 404, never
 * 403) and money moves only with an Idempotency-Key (§2e), amounts as minor-unit strings.
 */
class WalletControllerTest {

    static final String CREDS = "test-internal-credential";

    final UUID personId = UUID.randomUUID();
    final UUID tenantId = UUID.randomUUID();
    final UUID walletId = UUID.randomUUID();
    final UUID sagaId = UUID.randomUUID();
    final UUID billerId = UUID.randomUUID();
    final UUID chargeId = UUID.randomUUID();

    WalletRepository wallets = Mockito.mock(WalletRepository.class);
    EmiWalletService walletService = Mockito.mock(EmiWalletService.class);
    BillPaymentSagaEngine engine = Mockito.mock(BillPaymentSagaEngine.class);
    BillPaymentSagaRepository sagas = Mockito.mock(BillPaymentSagaRepository.class);
    BillerRepository billers = Mockito.mock(BillerRepository.class);
    ChargeRepository charges = Mockito.mock(ChargeRepository.class);
    ChargeService chargeService = Mockito.mock(ChargeService.class);
    WalletController controller = new WalletController(wallets, walletService, engine, sagas,
        billers, chargeService, charges);
    EmiIdentityFilter filter = new EmiIdentityFilter(CREDS);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors() // none
            .addFilters(filter)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    static MockHttpServletRequest trusted(String path, String method) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.addHeader("X-FSP-Internal-Credential", CREDS);
        req.addHeader("X-FSP-User-Id", "11111111-2222-4333-8444-555555555555");
        req.addHeader("X-FSP-Tenant-Id", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        req.addHeader("X-FSP-Username", "joe");
        req.addHeader("X-FSP-Client-Id", "web-spa");
        return req;
    }

    private Wallet ownWallet() {
        Wallet w = new Wallet();
        w.id = walletId;
        w.personId = personId;
        w.walletRef = "w-1";
        w.currency = "NAD";
        w.status = Wallet.Status.ACTIVE;
        return w;
    }

    private Charge charge(String state) {
        Charge c = new Charge();
        c.id = chargeId;
        c.walletId = walletId;
        c.personId = personId;
        c.orderRef = "ORD-1";
        c.currency = "NAD";
        c.merchMinor = java.math.BigDecimal.valueOf(100);
        c.feeMinor = java.math.BigDecimal.valueOf(10);
        c.taxMinor = java.math.BigDecimal.valueOf(5);
        c.totalMinor = java.math.BigDecimal.valueOf(115);
        c.priceFingerprint = "a".repeat(64);
        c.state = state;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return c;
    }

    // ------------------------------------------------------------------ filter

    @Test
    void filterRejectsRequestsWithoutInternalCredential() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/emi/wallets");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, Mockito.mock(FilterChain.class));
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("UNTRUSTED_ORIGIN");
    }

    @Test
    void filterRejectsWrongCredential() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/emi/wallets");
        req.addHeader("X-FSP-Internal-Credential", "wrong-credential");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, Mockito.mock(FilterChain.class));
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void filterRejectsMachinePlane() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/emi/wallets");
        req.addHeader("X-FSP-Internal-Credential", CREDS);
        req.addHeader("X-FSP-User-Id", "client:psp-worker");
        req.addHeader("X-FSP-Tenant-Id", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        req.addHeader("X-FSP-Username", "psp-worker");
        req.addHeader("X-FSP-Client-Id", "psp-worker");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, Mockito.mock(FilterChain.class));
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("ACTOR_TYPE_REJECTED");
    }

    @Test
    void filterDoesNotRequireActorOnInternalHops() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/ops/catalog");
        req.addHeader("X-FSP-Internal-Credential", CREDS);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    // ------------------------------------------------------------------ wallet

    @Test
    void walletViewServesOwnerWithStringBalance() throws Exception {
        Wallet w = ownWallet();
        when(wallets.findById(walletId)).thenReturn(Optional.of(w));
        when(walletService.balance(walletId)).thenReturn(175_00L);
        mvc.perform(get("/v1/emi/wallets/" + walletId)
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value("17500"));
    }

    @Test
    void foreignWalletAnswers404Not403() throws Exception {
        Wallet w = ownWallet();
        w.personId = UUID.randomUUID(); // someone else's
        when(wallets.findById(walletId)).thenReturn(Optional.of(w));
        mvc.perform(get("/v1/emi/wallets/" + walletId)
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web"))
            .andExpect(status().isNotFound()); // existence never leaks across owners
    }

    @Test
    void redeemRequiresIdempotencyKey() throws Exception {
        when(wallets.findById(walletId)).thenReturn(Optional.of(ownWallet()));
        mvc.perform(post("/v1/emi/wallets/" + walletId + "/redemptions")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":\"1000\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMI_REF_REQUIRED"));
        verify(walletService, never()).redeem(any(UUID.class), Mockito.anyLong(), any());
    }

    @Test
    void redeemPassesCallerKeyToService() throws Exception {
        when(wallets.findById(walletId)).thenReturn(Optional.of(ownWallet()));
        WalletJournal j = new WalletJournal();
        j.id = UUID.randomUUID();
        j.walletId = walletId;
        j.kind = WalletJournal.Kind.REDEMPTION;
        j.currency = "NAD";
        j.amountMinor = BigDecimal.valueOf(1000);
        j.state = WalletJournal.State.BOOKED;
        when(walletService.redeem(eq(walletId), eq(1000L), eq("key-1"))).thenReturn(j);
        mvc.perform(post("/v1/emi/wallets/" + walletId + "/redemptions")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":\"1000\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amountMinor").value("1000"))
            .andExpect(jsonPath("$.state").value(WalletJournal.State.BOOKED));
    }

    // ------------------------------------------------------------------ bills

    private BillPaymentSaga saga(String state) {
        BillPaymentSaga s = new BillPaymentSaga();
        s.id = sagaId;
        s.personId = personId;
        s.walletId = walletId;
        s.currency = "NAD";
        s.amountMinor = BigDecimal.valueOf(125_00);
        s.state = state;
        s.accountRef = "meter-1";
        s.billerId = billerId;
        return s;
    }

    @Test
    void payBillRequiresIdempotencyKey() throws Exception {
        when(wallets.findById(walletId)).thenReturn(Optional.of(ownWallet()));
        mvc.perform(post("/v1/emi/wallets/" + walletId + "/bills")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"billerCode\":\"DEMO-POWER\",\"accountRef\":\"meter-1\",\"amountMinor\":\"12500\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMI_REF_REQUIRED"));
        verify(engine, never()).start(any(), any(), any(), any(), Mockito.anyLong(), any());
    }

    @Test
    void payBillRunsLadderAndReturnsSaga() throws Exception {
        when(wallets.findById(walletId)).thenReturn(Optional.of(ownWallet()));
        when(engine.start(eq(personId), eq(walletId), eq("DEMO-POWER"), eq("meter-1"), eq(125_00L), eq("ik-1")))
            .thenReturn(new BillPaymentSagaEngine.StartResult(saga(BillPaymentSaga.State.HOLD), false));
        when(engine.submit(sagaId, personId)).thenReturn(saga(BillPaymentSaga.State.SUBMITTED));
        Biller b = new Biller();
        b.code = "DEMO-POWER";
        when(billers.findById(billerId)).thenReturn(Optional.of(b));
        mvc.perform(post("/v1/emi/wallets/" + walletId + "/bills")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .header("Idempotency-Key", "ik-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"billerCode\":\"DEMO-POWER\",\"accountRef\":\"meter-1\",\"amountMinor\":\"12500\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value(BillPaymentSaga.State.SUBMITTED))
            .andExpect(jsonPath("$.amountMinor").value("12500"))
            .andExpect(jsonPath("$.billerCode").value("DEMO-POWER"));
        // replay guard: replayed start must NOT reach the biller a second time
        when(engine.start(eq(personId), eq(walletId), eq("DEMO-POWER"), eq("meter-1"), eq(125_00L), eq("ik-1")))
            .thenReturn(new BillPaymentSagaEngine.StartResult(saga(BillPaymentSaga.State.HOLD), true));
        mvc.perform(post("/v1/emi/wallets/" + walletId + "/bills")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .header("Idempotency-Key", "ik-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"billerCode\":\"DEMO-POWER\",\"accountRef\":\"meter-1\",\"amountMinor\":\"12500\"}"))
            .andExpect(status().isOk());
        verify(engine, Mockito.times(1)).submit(any(UUID.class), any(UUID.class));
    }

    @Test
    void billHistorySupportsPaginationSortingSearch() throws Exception {
        when(wallets.findById(walletId)).thenReturn(Optional.of(ownWallet()));
        when(sagas.searchByWallet(eq(walletId), eq(null), eq("meter"),
            any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(saga(BillPaymentSaga.State.CONFIRMED))));
        mvc.perform(get("/v1/emi/wallets/" + walletId + "/bills")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .param("page", "0").param("size", "20").param("q", "meter")
                .param("sort", "createdAt,desc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].state").value(BillPaymentSaga.State.CONFIRMED));
        // §2f: unbounded or rogue sort fields are refused by the server
        mvc.perform(get("/v1/emi/wallets/" + walletId + "/bills")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .param("size", "101"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/emi/wallets/" + walletId + "/bills")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .param("sort", "lastFailureKind,desc"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void cancelOnForeignSagaAnswers404() throws Exception {
        BillPaymentSaga s = saga(BillPaymentSaga.State.PENDING_RECOGNITION);
        s.personId = UUID.randomUUID();
        when(sagas.findById(sagaId)).thenReturn(Optional.of(s));
        mvc.perform(post("/v1/emi/bills/" + sagaId + "/cancel")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web"))
            .andExpect(status().isNotFound());
        verify(engine, never()).voidSaga(any(), any(), any(), any());
    }

    @Test
    void ownerCancelInPrReturnsFourEyesProblem() throws Exception {
        when(sagas.findById(sagaId)).thenReturn(Optional.of(saga(BillPaymentSaga.State.PENDING_RECOGNITION)));
        when(engine.voidSaga(eq(sagaId), eq(personId), eq(null), any()))
            .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "EMI_FORCE_VOID_FOUR_EYES",
                "force-void needs two distinct humans"));
        mvc.perform(post("/v1/emi/bills/" + sagaId + "/cancel")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("EMI_FORCE_VOID_FOUR_EYES"));
    }

    @Test
    void chargeRequiresPriceFingerprint() throws Exception {
        when(wallets.findById(walletId)).thenReturn(Optional.of(ownWallet()));
        mvc.perform(post("/v1/emi/wallets/" + walletId + "/charges")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderRef\":\"ORD-1\",\"merchMinor\":\"100\",\"currency\":\"NAD\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMI_FINGERPRINT_REQUIRED"));
        verify(chargeService, never()).charge(any(), any(), any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), any(), any());
    }

    @Test
    void chargeServesOwnerWithStringAmounts() throws Exception {
        when(wallets.findById(walletId)).thenReturn(Optional.of(ownWallet()));
        when(chargeService.charge(eq(walletId), eq(personId), eq("ORD-1"), eq(100L), eq(10L), eq(5L),
            eq("NAD"), eq("a".repeat(64)))).thenReturn(charge(Charge.State.HOLD));
        mvc.perform(post("/v1/emi/wallets/" + walletId + "/charges")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderRef\":\"ORD-1\",\"merchMinor\":\"100\",\"feeMinor\":\"10\",\"taxMinor\":\"5\",\"currency\":\"NAD\",\"priceFingerprint\":\"" + "a".repeat(64) + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.merchMinor").value("100"))   // §2b string on the wire
            .andExpect(jsonPath("$.feeMinor").value("10"))
            .andExpect(jsonPath("$.taxMinor").value("5"))
            .andExpect(jsonPath("$.totalMinor").value("115"))
            .andExpect(jsonPath("$.state").value("HOLD"))
            .andExpect(jsonPath("$.priceFingerprint").isNotEmpty());
    }

    @Test
    void foreignChargeViewAnswers404() throws Exception {
        Charge foreign = charge(Charge.State.HOLD);
        foreign.personId = UUID.randomUUID();
        when(charges.findById(chargeId)).thenReturn(Optional.of(foreign));
        mvc.perform(get("/v1/emi/charges/" + chargeId)
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web"))
            .andExpect(status().isNotFound()); // existence never leaks across owners
    }

    @Test
    void confirmChargeServesStringAmounts() throws Exception {
        when(charges.findById(chargeId)).thenReturn(Optional.of(charge(Charge.State.HOLD)));
        when(chargeService.confirmCharge(eq(chargeId), eq(personId))).thenReturn(charge(Charge.State.CONFIRMED));
        mvc.perform(post("/v1/emi/charges/" + chargeId + "/confirm")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CONFIRMED"))
            .andExpect(jsonPath("$.totalMinor").value("115"));
    }

    @Test
    void chargeHistoryPaginationAndSortWhitelist() throws Exception {
        when(wallets.findById(walletId)).thenReturn(Optional.of(ownWallet()));
        var page = new org.springframework.data.domain.PageImpl<>(List.of(charge(Charge.State.HOLD)));
        when(charges.search(eq(walletId), any(), any(), any())).thenReturn(page);
        mvc.perform(get("/v1/emi/wallets/" + walletId + "/charges")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].totalMinor").value("115"));
        mvc.perform(get("/v1/emi/wallets/" + walletId + "/charges?sort=evilField,asc")
                .header("X-FSP-Internal-Credential", CREDS)
                .header("X-FSP-User-Id", personId.toString())
                .header("X-FSP-Tenant-Id", tenantId.toString())
                .header("X-FSP-Username", "joe")
                .header("X-FSP-Client-Id", "web"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMI_SORT_INVALID"));
    }
}
