package com.fsp.psp.api;

import com.fsp.psp.acquire.AcquireService;
import com.fsp.psp.acquire.Acquisition;
import com.fsp.psp.acquire.AcquisitionRepository;
import com.fsp.psp.common.ApiException;
import com.fsp.psp.merchant.Merchant;
import com.fsp.psp.merchant.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Merchant-acquiring surface (plan §11 Stage 5): authorize at the rail, capture on the
 * PSP book (two-phase pending chain), void pre-swept, and read views. Money on the wire
 * is minor units **as a string** (platform §2b). Tenant scoping is enforced here: an
 * acquisition of another tenant is a 404.
 */
@RestController
public class AcquireController {

    private final AcquireService acquireService;
    private final MerchantService merchants;
    private final AcquisitionRepository acquisitions;

    public AcquireController(AcquireService acquireService, MerchantService merchants,
                             AcquisitionRepository acquisitions) {
        this.acquireService = acquireService;
        this.merchants = merchants;
        this.acquisitions = acquisitions;
    }

    @PspOperation(id = "psp.acquire.authorize", risk = PspOperation.Risk.WRITE,
        title = "Authorize a merchant sale at the rail")
    @PostMapping(value = "/v1/psp/merchants/{merchantId}/acquisitions",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authorize(HttpServletRequest http, @PathVariable UUID merchantId,
                                         @RequestBody AcquireRequest body,
                                         @RequestHeader(value = "Idempotency-Key",
                                             required = false) String idemKey) {
        PspActor actor = actor(http);
        merchants.byId(actor.tenantId(), merchantId); // tenant scoping first (404)
        var result = acquireService.authorize(actor.tenantId(), merchantId,
            body.connectorCode(), body.pspRef(), body.grossMinor(), body.feeMinor(),
            body.currency(), idemKey);
        Map<String, Object> out = view(result.acquisition());
        out.put("replay", result.replay());
        return out;
    }

    @PspOperation(id = "psp.acquire.capture", risk = PspOperation.Risk.WRITE,
        title = "Capture an authorized sale onto the PSP clearing book")
    @PostMapping(value = "/v1/psp/acquisitions/{acquisitionId}/capture",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> capture(HttpServletRequest http, @PathVariable UUID acquisitionId) {
        PspActor actor = actor(http);
        return view(acquireService.capture(actor.tenantId(), acquisitionId));
    }

    @PspOperation(id = "psp.acquire.void", risk = PspOperation.Risk.WRITE,
        title = "Void a not-yet-settled acquisition")
    @PostMapping(value = "/v1/psp/acquisitions/{acquisitionId}/void",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> voidAcquisition(HttpServletRequest http,
                                               @PathVariable UUID acquisitionId) {
        PspActor actor = actor(http);
        return view(acquireService.voidAcquisition(actor.tenantId(), acquisitionId));
    }

    @PspOperation(id = "psp.acquire.view", title = "View an acquisition")
    @GetMapping(value = "/v1/psp/acquisitions/{acquisitionId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> get(HttpServletRequest http, @PathVariable UUID acquisitionId) {
        PspActor actor = actor(http);
        return view(acquireService.owned(actor.tenantId(), acquisitionId));
    }

    @PspOperation(id = "psp.acquire.history", title = "List a merchant's acquisitions")
    @GetMapping(value = "/v1/psp/merchants/{merchantId}/acquisitions",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> history(HttpServletRequest http, @PathVariable UUID merchantId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String state,
                                       @RequestParam(required = false) String q) {
        PspActor actor = actor(http);
        Page<Acquisition> result = acquireService.history(actor.tenantId(), merchantId,
            page, size, sort, state, q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("totalElements", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        out.put("acquisitions", result.getContent().stream().map(AcquireController::view).toList());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, Object> view(Acquisition a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.id.toString());
        out.put("merchantId", a.merchantId.toString());
        out.put("connectorCode", a.connectorCode);
        out.put("currency", a.currency);
        out.put("grossMinor", money(a.grossMinor));
        out.put("feeMinor", money(a.feeMinor));
        out.put("netMinor", money(a.netMinor));
        out.put("pspRef", a.pspRef);
        out.put("railRef", a.railRef);
        out.put("state", a.state);
        out.put("cycleDate", a.cycleDate == null ? null : a.cycleDate.toString());
        out.put("failureCode", a.failureCode);
        out.put("createdAt", a.createdAt.toString());
        return out;
    }

    /** §2b: minor units cross the wire as strings (JS-safe). */
    static String money(BigDecimal v) {
        return v.longValueExact() + "";
    }

    static PspActor actor(HttpServletRequest http) {
        return MerchantController.actor(http);
    }

    public record AcquireRequest(String connectorCode, String pspRef, long grossMinor,
                                 long feeMinor, String currency) {}
}
