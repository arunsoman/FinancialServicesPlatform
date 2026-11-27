package com.fsp.core.orders;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * Core SKU order surface (plan §7.1) — the order brain's REST face. Owner-scoped
 * (buyer_person_id + tenant subtree), empty permission = authenticated person-plane
 * self-service, money as minor-unit strings (§2b). Collection endpoints are bounded
 * server-side (§2f): pagination + sort whitelist + q search pushed to the database.
 */
@RestController
@RequestMapping("/v1/tenants/{tenantId}/sku-orders")
public class SkuOrderController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "state", "totalMinor", "orderRef");
    private static final int MAX_PAGE_SIZE = 100;

    private final SkuOrderService service;
    private final SkuOrderRepository orders;

    public SkuOrderController(SkuOrderService service, SkuOrderRepository orders) {
        this.service = service;
        this.orders = orders;
    }

    @FspOperation(id = "sku.order.charge", component = "orders",
        risk = FspOperation.OperationRisk.WRITE, title = "Create a SKU order and charge the wallet (linked hold)")
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> begin(HttpServletRequest http, @PathVariable UUID tenantId,
                                     @RequestBody QuoteRequest body) {
        Actor actor = actor(http);
        requireTenant(actor, tenantId);
        SkuOrder order = service.begin(tenantId, actor, body.orderRef(), safe(body.skuCode()),
            body.description() == null ? body.skuCode() : body.description(),
            body.bookId(), body.walletId(), safe(body.currency()),
            amount(body.merchMinor(), "merchMinor"), amount(body.feeMinor() == null ? BigDecimal.ZERO : body.feeMinor(), "feeMinor"),
            amount(body.taxMinor() == null ? BigDecimal.ZERO : body.taxMinor(), "taxMinor"));
        return view(order);
    }

    @FspOperation(id = "sku.order.confirm", component = "orders",
        risk = FspOperation.OperationRisk.WRITE, title = "Confirm a SKU order (commits the charge)")
    @PostMapping(value = "/{orderId}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> confirm(HttpServletRequest http, @PathVariable UUID tenantId,
                                       @PathVariable UUID orderId) {
        Actor actor = actor(http);
        requireTenant(actor, tenantId);
        return view(service.confirm(orderId, actor));
    }

    @FspOperation(id = "sku.order.cancel", component = "orders",
        risk = FspOperation.OperationRisk.WRITE, title = "Cancel a SKU order (release the charge hold)")
    @PostMapping(value = "/{orderId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cancel(HttpServletRequest http, @PathVariable UUID tenantId,
                                      @PathVariable UUID orderId) {
        Actor actor = actor(http);
        requireTenant(actor, tenantId);
        return view(service.cancel(orderId, actor));
    }

    @FspOperation(id = "sku.order.sync", component = "orders",
        risk = FspOperation.OperationRisk.WRITE, title = "Sync order state from EMI money truth")
    @PostMapping(value = "/{orderId}/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> sync(HttpServletRequest http, @PathVariable UUID tenantId,
                                    @PathVariable UUID orderId) {
        Actor actor = actor(http);
        requireTenant(actor, tenantId);
        return view(service.sync(orderId, actor));
    }

    @FspOperation(id = "sku.order.mirror", component = "orders",
        risk = FspOperation.OperationRisk.WRITE, title = "Post the mirror JE (price fingerprint audit)")
    @PostMapping(value = "/{orderId}/mirror-journal", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> mirror(HttpServletRequest http, @PathVariable UUID tenantId,
                                      @PathVariable UUID orderId) {
        Actor actor = actor(http);
        requireTenant(actor, tenantId);
        return view(service.chargeIntent(orderId, actor));
    }

    @FspOperation(id = "sku.order.view", component = "orders", title = "View a SKU order")
    @GetMapping(value = "/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> view(HttpServletRequest http, @PathVariable UUID tenantId,
                                    @PathVariable UUID orderId) {
        Actor actor = actor(http);
        requireTenant(actor, tenantId);
        return view(ownedOrder(orderId, actor));
    }

    @FspOperation(id = "sku.order.history", component = "orders", title = "List SKU orders")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> history(HttpServletRequest http, @PathVariable UUID tenantId,
                                       @RequestParam(value = "page", defaultValue = "0") int page,
                                       @RequestParam(value = "size", defaultValue = "20") int size,
                                       @RequestParam(value = "sort", required = false) String sort,
                                       @RequestParam(value = "state", required = false) String state,
                                       @RequestParam(value = "q", required = false) String q) {
        Actor actor = actor(http);
        requireTenant(actor, tenantId);
        PageRequest pr = pageRequest(page, size, sort);
        String st = state == null ? null : state.strip();
        Page<SkuOrder> result = orders.search(tenantId, st.isEmpty() ? null : st,
            q == null ? null : q.strip().toLowerCase(), pr);
        // q is scoped to the caller's tenant; a foreign owner's rows can never appear
        List<Map<String, Object>> owned = result.getContent().stream()
            .filter(o -> o.buyerPersonId.equals(actor.personId()))
            .map(this::view).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", owned);
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("totalElements", result.getTotalElements()); // tenant-wide count is not sensitive
        out.put("totalPages", result.getTotalPages());
        return out;
    }

    // ------------------------------------------------------------------ plumbing

    /** The quote binds the breakdown; EMI re-hashes it — a mismatch is the repricing alarm. */
    public record QuoteRequest(String orderRef, String skuCode, String description, UUID bookId,
                               UUID walletId, String currency, BigDecimal merchMinor,
                               BigDecimal feeMinor, BigDecimal taxMinor) {}

    private Map<String, Object> view(SkuOrder o) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", o.id.toString());
        out.put("tenantId", o.tenantId.toString());
        out.put("orderRef", o.orderRef);
        out.put("skuCode", o.skuCode);
        if (o.description != null) out.put("description", o.description);
        out.put("walletId", o.walletId.toString());
        out.put("currency", o.currency);
        out.put("merchMinor", o.merchMinor.longValueExact() + ""); // §2b string on the wire
        out.put("feeMinor", o.feeMinor.longValueExact() + "");
        out.put("taxMinor", o.taxMinor.longValueExact() + "");
        out.put("totalMinor", o.totalMinor.longValueExact() + "");
        out.put("priceFingerprint", o.priceFingerprint);
        out.put("state", o.state);
        if (o.chargeId != null) out.put("chargeId", o.chargeId.toString());
        if (o.journalEntryId != null) out.put("journalEntryId", o.journalEntryId.toString());
        if (o.failureCode != null) out.put("failureCode", o.failureCode);
        return out;
    }

    private SkuOrder ownedOrder(UUID orderId, Actor actor) {
        SkuOrder order = orders.findById(orderId)
            .orElseThrow(() -> ApiException.notFound("SKU_ORDER_NOT_FOUND", "order not found"));
        if (!order.buyerPersonId.equals(actor.personId())) {
            throw ApiException.notFound("SKU_ORDER_NOT_FOUND", "order not found"); // 404, never 403
        }
        return order;
    }

    private static Actor actor(HttpServletRequest http) {
        Object a = http.getAttribute("fsp.actor");
        if (!(a instanceof Actor actor)) {
            throw ApiException.unauthorized("ACTOR_MISSING", "no verified actor on the request");
        }
        return actor;
    }

    private static void requireTenant(Actor actor, UUID tenantId) {
        if (!tenantId.equals(actor.activeTenantId())) {
            throw ApiException.notFound("TENANT_NOT_FOUND", "tenant not found"); // subtree isolation
        }
    }

    private static long amount(BigDecimal raw, String field) {
        if (raw == null) {
            throw ApiException.badRequest("SKU_AMOUNT_INVALID", field + " must be present");
        }
        if (raw.signum() < 0) {
            throw ApiException.badRequest("SKU_AMOUNT_INVALID", field + " must be non-negative");
        }
        long v;
        try {
            v = raw.longValueExact();
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("SKU_AMOUNT_INVALID", field + " must be minor units");
        }
        return v;
    }

    private static String safe(String v) {
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest("SKU_INPUT_INVALID", "text fields must be 1-256 chars");
        }
        String s = v.strip();
        if (s.length() > 256) {
            throw ApiException.badRequest("SKU_INPUT_INVALID", "text fields must be 1-256 chars");
        }
        return s;
    }

    private static PageRequest pageRequest(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("SKU_PAGE_INVALID", "page/size out of range (size max " + MAX_PAGE_SIZE + ")");
        }
        String field = "createdAt";
        Sort.Direction dir = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            if (!SORTABLE.contains(parts[0])) {
                throw ApiException.badRequest("SKU_SORT_INVALID", "sort field not permitted");
            }
            field = parts[0];
            if (parts.length == 2) {
                if ("asc".equalsIgnoreCase(parts[1].strip())) dir = Sort.Direction.ASC;
                else if ("desc".equalsIgnoreCase(parts[1].strip())) dir = Sort.Direction.DESC;
                else throw ApiException.badRequest("SKU_SORT_INVALID", "sort direction must be asc|desc");
            }
        }
        return PageRequest.of(page, size, Sort.by(dir, field));
    }
}
