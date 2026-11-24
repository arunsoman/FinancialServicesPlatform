package com.fsp.emi.api;

import com.fsp.emi.bill.BillerRepository;
import com.fsp.emi.bill.BillPaymentSaga;
import com.fsp.emi.bill.BillPaymentSagaRepository;
import com.fsp.emi.bill.BillPaymentSagaEngine;
import com.fsp.emi.charge.Charge;
import com.fsp.emi.charge.ChargeRepository;
import com.fsp.emi.charge.ChargeService;
import com.fsp.emi.common.ApiException;
import com.fsp.emi.wallet.EmiWalletService;
import com.fsp.emi.wallet.Wallet;
import com.fsp.emi.wallet.WalletJournal;
import com.fsp.emi.wallet.WalletRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * EMI self-service REST (chapter 13 Stage 2+3 wire): owner-scoped wallet + bill-payment
 * surface. Every op is person-plane, authenticated at the gateway (DPoP-constrained
 * token) and re-verified HERE against database truth — the actor may only see and mutate
 * wallets and sagas they own; anything else answers 404 (existence is not leaked across
 * owners). Money on the wire is minor units **as a string** (JS-safe, platform §2b).
 */
@RestController
public class WalletController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "amountMinor", "state", "submittedAt",
        "totalMinor", "orderRef");
    private static final int MAX_PAGE_SIZE = 100;

    private final WalletRepository wallets;
    private final EmiWalletService walletService;
    private final BillPaymentSagaEngine sagaEngine;
    private final BillPaymentSagaRepository sagas;
    private final BillerRepository billers;
    private final ChargeService chargeService;
    private final ChargeRepository charges;

    public WalletController(WalletRepository wallets, EmiWalletService walletService,
                            BillPaymentSagaEngine sagaEngine, BillPaymentSagaRepository sagas,
                            BillerRepository billers, ChargeService chargeService,
                            ChargeRepository charges) {
        this.wallets = wallets;
        this.walletService = walletService;
        this.sagaEngine = sagaEngine;
        this.sagas = sagas;
        this.billers = billers;
        this.chargeService = chargeService;
        this.charges = charges;
    }

    // ------------------------------------------------------------------ wallet

    @EmiOperation(id = "emi.wallet.open", risk = EmiOperation.Risk.WRITE,
        title = "Open an e-money wallet")
    @PostMapping(value = "/v1/emi/wallets", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> openWallet(HttpServletRequest http) {
        EmiActor actor = actor(http);
        Wallet w = walletService.open(actor.personId(), "w-" + UUID.randomUUID());
        return walletView(w);
    }

    /**
     * The acting person's own NAD wallet (one per person and currency). Lets core offer
     * "pay from your wallet" without the browser ever holding a wallet id; 404 when the
     * person has none — the same answer a foreign wallet gets (existence never leaks).
     */
    @EmiOperation(id = "emi.wallet.mine", title = "View own wallet (by owner)")
    @GetMapping(value = "/v1/emi/wallets/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> myWallet(HttpServletRequest http) {
        EmiActor actor = actor(http);
        Wallet w = wallets.findByPersonIdAndCurrency(actor.personId(), "NAD")
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMI_WALLET_NOT_FOUND", "wallet not found"));
        return walletView(w);
    }

    @EmiOperation(id = "emi.wallet.view", title = "View own wallet balance")
    @GetMapping(value = "/v1/emi/wallets/{walletId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> wallet(HttpServletRequest http, @PathVariable UUID walletId) {
        EmiActor actor = actor(http);
        return walletView(ownedWallet(actor, walletId));
    }

    @EmiOperation(id = "emi.wallet.redeem", risk = EmiOperation.Risk.WRITE,
        title = "Redeem e-money at par (PSD-3 §9.1.1)")
    @PostMapping(value = "/v1/emi/wallets/{walletId}/redemptions",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> redeem(HttpServletRequest http, @PathVariable UUID walletId,
                                      @RequestBody RedeemRequest body,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_REF_REQUIRED",
                "redemption requires an Idempotency-Key header");
        }
        EmiActor actor = actor(http);
        ownedWallet(actor, walletId);
        long amount = amountOf(body);
        WalletJournal j = walletService.redeem(walletId, amount, idemKey);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", j.id.toString());
        out.put("walletId", j.walletId.toString());
        out.put("kind", j.kind);
        out.put("currency", j.currency);
        out.put("amountMinor", j.amountMinor.longValueExact() + ""); // §2b string on the wire
        out.put("state", j.state);
        return out;
    }

    // ------------------------------------------------------------------ bills

    @EmiOperation(id = "emi.bill.pay", risk = EmiOperation.Risk.WRITE,
        title = "Pay a bill from an own wallet")
    @PostMapping(value = "/v1/emi/wallets/{walletId}/bills", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> payBill(HttpServletRequest http, @PathVariable UUID walletId,
                                       @RequestBody PayRequest body,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_REF_REQUIRED",
                "bill payment requires an Idempotency-Key header");
        }
        EmiActor actor = actor(http);
        ownedWallet(actor, walletId);
        long amount = amountOf(body);
        BillPaymentSagaEngine.StartResult started = sagaEngine.start(actor.personId(), walletId,
            safe(body.billerCode()), safe(body.accountRef()), amount, idemKey);
        BillPaymentSaga saga = started.saga();
        if (!started.replayed()) {
            saga = sagaEngine.submit(saga.id, actor.personId()); // HOLD→SUBMITTED (demo/rate-limit stays HOLD)
        }
        return sagaView(saga);
    }

    @EmiOperation(id = "emi.bill.history", title = "List own bill payments")
    @GetMapping(value = "/v1/emi/wallets/{walletId}/bills", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> billHistory(HttpServletRequest http, @PathVariable UUID walletId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(required = false) String sort,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String q) {
        EmiActor actor = actor(http);
        ownedWallet(actor, walletId);
        PageRequest pr = pageRequest(page, size, sort, Sort.Direction.DESC, "createdAt");
        String ql = q == null ? null : safe(q).toLowerCase();
        String st = state == null ? null : safe(state);
        Page<BillPaymentSaga> p = sagas.searchByWallet(walletId, st, ql, pr);
        List<Map<String, Object>> content = p.getContent().stream().map(this::sagaView).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        out.put("totalElements", p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        return out;
    }

    @EmiOperation(id = "emi.bill.view", title = "View own bill payment status")
    @GetMapping(value = "/v1/emi/bills/{sagaId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> bill(HttpServletRequest http, @PathVariable UUID sagaId) {
        EmiActor actor = actor(http);
        return sagaView(ownedSaga(actor, sagaId));
    }

    @EmiOperation(id = "emi.bill.refresh", title = "Refresh own bill payment recognition")
    @PostMapping(value = "/v1/emi/bills/{sagaId}/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> probe(HttpServletRequest http, @PathVariable UUID sagaId) {
        EmiActor actor = actor(http);
        ownedSaga(actor, sagaId);
        return sagaView(sagaEngine.probeDisambiguate(sagaId, actor.personId()));
    }

    @EmiOperation(id = "emi.bill.cancel", risk = EmiOperation.Risk.WRITE,
        title = "Cancel own bill payment (hold release)")
    @PostMapping(value = "/v1/emi/bills/{sagaId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cancel(HttpServletRequest http, @PathVariable UUID sagaId) {
        EmiActor actor = actor(http);
        ownedSaga(actor, sagaId);
        return sagaView(sagaEngine.voidSaga(sagaId, actor.personId(), null, "customer cancel"));
    }

    // ------------------------------------------------------------------ helpers

    private static EmiActor actor(HttpServletRequest http) {
        Object a = http.getAttribute(EmiIdentityFilter.ACTOR_ATTR);
        if (!(a instanceof EmiActor actor)) {
            // unreachable behind EmiIdentityFilter — defensive only
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "ACTOR_MISSING",
                "no verified actor on the request");
        }
        return actor;
    }

    private Wallet ownedWallet(EmiActor actor, UUID walletId) {
        Wallet w = wallets.findById(walletId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMI_WALLET_NOT_FOUND",
                "wallet not found"));
        if (!actor.personId().equals(w.personId)) {
            // existence is never leaked across owners — same answer as a foreign id
            throw new ApiException(HttpStatus.NOT_FOUND, "EMI_WALLET_NOT_FOUND", "wallet not found");
        }
        return w;
    }

    private BillPaymentSaga ownedSaga(EmiActor actor, UUID sagaId) {
        BillPaymentSaga s = sagas.findById(sagaId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMI_SAGA_NOT_FOUND",
                "bill payment not found"));
        if (!actor.personId().equals(s.personId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EMI_SAGA_NOT_FOUND", "bill payment not found");
        }
        return s;
    }

    private long amountOf(BigDecimal raw, String field) {
        if (raw == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT_INVALID", field + " must be present");
        }
        // zero legs are legal (single-leg chain); only negatives are invalid
        if (raw.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT_INVALID", field + " must be non-negative");
        }
        long amount;
        try {
            amount = raw.longValueExact();
        } catch (ArithmeticException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT_INVALID", field + " must be minor units");
        }
        if (amount > 100_000_000_00L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT_CAP", field + " exceeds movement cap");
        }
        return amount;
    }

    private long amountOf(Object request) {
        BigDecimal raw = request instanceof PayRequest ? ((PayRequest) request).amountMinor()
            : request instanceof RedeemRequest ? ((RedeemRequest) request).amountMinor() : null;
        if (raw == null || raw.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT_INVALID", "amount must be positive");
        }
        long amount;
        try {
            amount = raw.longValueExact();
        } catch (ArithmeticException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT_INVALID", "amount must be minor units");
        }
        if (amount > 100_000_000_00L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT_CAP", "amount exceeds movement cap");
        }
        return amount;
    }

    /** Header-provided free text is reduced to the safe token class before touching entities. */
    private static String safe(String v) {
        if (v == null) return null;
        String s = v.strip();
        if (s.isEmpty() || s.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_INPUT_INVALID",
                "text fields must be 1-128 chars");
        }
        return s;
    }

    private PageRequest pageRequest(int page, int size, String sort, Sort.Direction dir, String defaultField) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_PAGE_INVALID",
                "page/size out of range (size max " + MAX_PAGE_SIZE + ")");
        }
        String field = defaultField;
        Sort.Direction direction = dir;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            if (!SORTABLE.contains(parts[0])) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_SORT_INVALID",
                    "sort field not permitted");
            }
            field = parts[0];
            if (parts.length == 2 && "asc".equalsIgnoreCase(parts[1].strip())) {
                direction = Sort.Direction.ASC;
            } else if (parts.length == 2 && "desc".equalsIgnoreCase(parts[1].strip())) {
                direction = Sort.Direction.DESC;
            } else if (parts.length == 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_SORT_INVALID",
                    "sort direction must be asc|desc");
            }
        }
        return PageRequest.of(page, size, Sort.by(direction, field));
    }

    private Map<String, Object> walletView(Wallet w) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", w.id.toString());
        out.put("walletRef", w.walletRef);
        out.put("currency", w.currency);
        out.put("status", w.status);
        out.put("balance", walletService.balance(w.id) + ""); // §2b string on the wire
        return out;
    }

    private Map<String, Object> sagaView(BillPaymentSaga s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id.toString());
        out.put("walletId", s.walletId.toString());
        out.put("currency", s.currency);
        out.put("amountMinor", s.amount() + ""); // §2b string on the wire
        out.put("state", s.state);
        if (s.railRef != null) out.put("railRef", s.railRef);
        if (s.accountRef != null) out.put("accountRef", s.accountRef);
        billers.findById(s.billerId).ifPresent(b -> out.put("billerCode", b.code));
        if (s.submittedAt != null) out.put("submittedAt", s.submittedAt.toString());
        if (s.settledAt != null) out.put("settledAt", s.settledAt.toString());
        if (s.expiresAt != null) out.put("expiresAt", s.expiresAt.toString());
        if (s.reversalClass != null) out.put("reversalClass", s.reversalClass);
        if (s.lastFailureKind != null) out.put("lastFailureKind", s.lastFailureKind);
        return out;
    }

    public record PayRequest(String billerCode, String accountRef, BigDecimal amountMinor) {
        /** Explicit zero-arg canonical constructor not required; Jackson needs the fields only. */
    }

    public record RedeemRequest(BigDecimal amountMinor) {}

    // ------------------------------------------------------------------ charges (Stage 4 SKU money)

    /**
     * Wallet-funded SKU order charge (core drives this as the buyer — plan §7.1). The body
     * must hash to core's price fingerprint; the hold is a LINKED PENDING chain.
     */
    @EmiOperation(id = "emi.order.charge", risk = EmiOperation.Risk.WRITE,
        title = "Charge an own wallet for a SKU order (linked hold)")
    @PostMapping(value = "/v1/emi/wallets/{walletId}/charges", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chargeOrder(HttpServletRequest http, @PathVariable UUID walletId,
                                           @RequestBody ChargeRequest body) {
        EmiActor actor = actor(http);
        ownedWallet(actor, walletId);
        if (body.priceFingerprint == null || body.priceFingerprint.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_FINGERPRINT_REQUIRED",
                "price fingerprint is mandatory on order charges");
        }
        Charge c = chargeService.charge(walletId, actor.personId(), body.orderRef,
            amountOf(body.merchMinor, "merchMinor"), amountOf(body.feeMinor == null ? BigDecimal.ZERO : body.feeMinor, "feeMinor"),
            amountOf(body.taxMinor == null ? BigDecimal.ZERO : body.taxMinor, "taxMinor"), safe(body.currency), body.priceFingerprint.strip());
        return chargeView(c);
    }

    @EmiOperation(id = "emi.order.confirm", risk = EmiOperation.Risk.WRITE,
        title = "Confirm a held order charge (fulfilment precondition flipped)")
    @PostMapping(value = "/v1/emi/charges/{chargeId}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> confirmCharge(HttpServletRequest http, @PathVariable UUID chargeId) {
        EmiActor actor = actor(http);
        return chargeView(chargeService.confirmCharge(chargeId, actor.personId()));
    }

    @EmiOperation(id = "emi.order.cancel", risk = EmiOperation.Risk.WRITE,
        title = "Cancel a held order charge (hold release)")
    @PostMapping(value = "/v1/emi/charges/{chargeId}/void", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> voidCharge(HttpServletRequest http, @PathVariable UUID chargeId) {
        EmiActor actor = actor(http);
        return chargeView(chargeService.voidCharge(chargeId, actor.personId()));
    }

    @EmiOperation(id = "emi.order.view", title = "View an own order charge")
    @GetMapping(value = "/v1/emi/charges/{chargeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> viewCharge(HttpServletRequest http, @PathVariable UUID chargeId) {
        EmiActor actor = actor(http);
        Charge c = charges.findById(chargeId).orElseThrow(() ->
            new ApiException(HttpStatus.NOT_FOUND, "EMI_NOT_FOUND",
                "resource does not exist for this actor"));
        if (!actor.personId().equals(c.personId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EMI_NOT_FOUND",
                "resource does not exist for this actor"); // existence never leaks across owners
        }
        return chargeView(c);
    }

    @EmiOperation(id = "emi.order.history", title = "List own order charges")
    @GetMapping(value = "/v1/emi/wallets/{walletId}/charges", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chargeHistory(HttpServletRequest http, @PathVariable UUID walletId,
                                             @RequestParam(value = "page", defaultValue = "0") int page,
                                             @RequestParam(value = "size", defaultValue = "20") int size,
                                             @RequestParam(value = "sort", required = false) String sort,
                                             @RequestParam(value = "state", required = false) String state,
                                             @RequestParam(value = "q", required = false) String q) {
        EmiActor actor = actor(http);
        ownedWallet(actor, walletId);
        var dir = sort != null && sort.endsWith(",asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        String st = state == null ? null : safe(state);
        String ql = q == null ? null : safe(q);
        Page<Charge> result = charges.search(walletId, st, ql, pageRequest(page, size, sort, dir, "createdAt"));
        List<Map<String, Object>> content = result.getContent().stream().map(this::chargeView).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("totalElements", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        return out;
    }

    private Map<String, Object> chargeView(Charge c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id.toString());
        out.put("walletId", c.walletId.toString());
        out.put("orderRef", c.orderRef);
        out.put("currency", c.currency);
        out.put("merchMinor", c.merchMinor.longValueExact() + ""); // §2b string on the wire
        out.put("feeMinor", c.feeMinor.longValueExact() + "");
        out.put("taxMinor", c.taxMinor.longValueExact() + "");
        out.put("totalMinor", c.totalMinor.longValueExact() + "");
        out.put("state", c.state);
        if (c.priceFingerprint != null) out.put("priceFingerprint", c.priceFingerprint);
        if (c.createdAt != null) out.put("createdAt", c.createdAt.toString());
        return out;
    }

    public record ChargeRequest(String orderRef, BigDecimal merchMinor,
                                BigDecimal feeMinor, BigDecimal taxMinor,
                                String currency, String priceFingerprint) {}
}
