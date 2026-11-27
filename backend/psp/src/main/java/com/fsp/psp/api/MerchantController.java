package com.fsp.psp.api;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merchant onboarding + lifecycle (plan §11 Stage 5). Person-plane, tenant-scoped: the
 * actor sees (and mutates) only merchants of their own tenant — anything else answers
 * 404 so existence is never leaked across tenants (platform §4).
 */
@RestController
public class MerchantController {

    private final MerchantService merchants;

    public MerchantController(MerchantService merchants) {
        this.merchants = merchants;
    }

    @PspOperation(id = "psp.merchant.onboard", risk = PspOperation.Risk.WRITE,
        title = "Onboard a merchant onto the PSP acquiring plane")
    @PostMapping(value = "/v1/psp/merchants", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> onboard(HttpServletRequest http, @RequestBody Map<String, Object> body) {
        PspActor actor = actor(http);
        Merchant m = merchants.onboard(actor.tenantId(),
            str(body, "merchantCode"), str(body, "displayName"),
            str(body, "merchantBankAccountRef"), intOrNull(body, "payOverDays"));
        return view(m);
    }

    @PspOperation(id = "psp.merchant.view", title = "View a tenant merchant")
    @GetMapping(value = "/v1/psp/merchants/{merchantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> get(HttpServletRequest http, @PathVariable UUID merchantId) {
        PspActor actor = actor(http);
        return view(merchants.byId(actor.tenantId(), merchantId));
    }

    @PspOperation(id = "psp.merchant.history", title = "List tenant merchants")
    @GetMapping(value = "/v1/psp/merchants", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> list(HttpServletRequest http,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) String q) {
        PspActor actor = actor(http);
        Page<Merchant> result = merchants.list(actor.tenantId(), page, size, sort, q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("totalElements", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        out.put("merchants", result.getContent().stream().map(MerchantController::view).toList());
        return out;
    }

    @PspOperation(id = "psp.merchant.activate", risk = PspOperation.Risk.WRITE,
        title = "Activate a merchant (PENDING → ACTIVE)")
    @PostMapping(value = "/v1/psp/merchants/{merchantId}/activate",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> activate(HttpServletRequest http, @PathVariable UUID merchantId) {
        PspActor actor = actor(http);
        return view(merchants.activate(actor.tenantId(), merchantId));
    }

    @PspOperation(id = "psp.merchant.suspend", risk = PspOperation.Risk.WRITE,
        title = "Suspend a merchant (ACTIVE → SUSPENDED)")
    @PostMapping(value = "/v1/psp/merchants/{merchantId}/suspend",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> suspend(HttpServletRequest http, @PathVariable UUID merchantId) {
        PspActor actor = actor(http);
        return view(merchants.suspend(actor.tenantId(), merchantId));
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, Object> view(Merchant m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id.toString());
        out.put("merchantCode", m.merchantCode);
        out.put("displayName", m.displayName);
        out.put("status", m.status);
        out.put("payOverDays", m.payOverDays);
        out.put("hasBankAccountRef", m.merchantBankAccountRef != null);
        out.put("createdAt", m.createdAt.toString());
        return out;
    }

    static PspActor actor(HttpServletRequest http) {
        Object a = http.getAttribute(PspIdentityFilter.ACTOR_ATTR);
        if (!(a instanceof PspActor actor)) {
            // unreachable behind PspIdentityFilter — defensive only
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACTOR_MISSING",
                "no verified actor on the request");
        }
        return actor;
    }

    static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    static Integer intOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_INPUT_INVALID",
                key + " must be an integer");
        }
    }
}
