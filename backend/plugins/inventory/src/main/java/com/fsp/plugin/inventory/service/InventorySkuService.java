package com.fsp.plugin.inventory.service;

import com.fsp.plugin.inventory.api.ApiProblemException;
import com.fsp.plugin.inventory.api.InventoryDtos;
import com.fsp.plugin.inventory.domain.InventoryEvent;
import com.fsp.plugin.inventory.domain.InventoryProduct;
import com.fsp.plugin.inventory.repository.IdempotencyRecordRepository;
import com.fsp.plugin.inventory.repository.InventoryEventRepository;
import com.fsp.plugin.inventory.repository.InventoryProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SKU catalog (physical plane). Pricing goes through four-eyes (PLUGIN:SKU_PRICING):
 * the maker creates the SKU in PENDING_APPROVAL and opens the plugin approval; the checker's
 * decision resolves the approval row (maker ≠ checker re-verified in core at real person_id).
 */
@Slf4j
@Service
public class InventorySkuService {

    /** Public sort names → pinned columns (AGENTS §2f: never a client-supplied column). */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "code", "code", "name", "name", "unit", "unit",
        "createdAt", "createdAt", "updatedAt", "updatedAt");
    private static final Set<String> ACTIONS = Set.of("APPROVE", "REJECT");

    private final InventoryProductRepository products;
    private final InventoryEventRepository events;
    private final IdempotencyRecordRepository idempotency;
    private final CoreClient core;

    public InventorySkuService(InventoryProductRepository products, InventoryEventRepository events,
                               IdempotencyRecordRepository idempotency, CoreClient core) {
        this.products = products;
        this.events = events;
        this.idempotency = idempotency;
        this.core = core;
    }

    public InventoryDtos.ItemList<InventoryDtos.SkuView> list(UUID tenantId, int page, int size,
                                                              String q, String status, String sort) {
        Pageable pageable = Paging.page(SORT_COLUMNS, sort, page, size);
        Page<InventoryProduct> p = products.search(tenantId, q != null && !q.isBlank(),PluginSupport.searchPattern(q),
            Paging.status(status, InventoryProduct.VALID_STATUSES), pageable);
        return Paging.pageOf(p.map(this::view), size);
    }

    public InventoryDtos.SkuView get(UUID tenantId, UUID id) {
        return view(load(tenantId, id));
    }

    /** maker: create the SKU + open the PLUGIN:SKU_PRICING approval (idempotent on Idempotency-Key). */
    @Transactional
    public InventoryDtos.SkuView create(UUID tenantId, UUID personId, InventoryDtos.CreateSkuRequest req,
                                        String assertion, String idempotencyKey) {
        PluginSupport.requireFresh(idempotency, personId, idempotencyKey);
        String code = PluginSupport.nonBlank(req.code(), "code");
        if (products.existsByOwnerTenantIdAndCode(tenantId, code)) {
            throw ApiProblemException.conflict("SKU_EXISTS", "SKU code already in use");
        }
        long cost = PluginSupport.money(req.unitCostMinor(), "unitCostMinor");
        long wholesale = PluginSupport.money(req.wholesalePriceMinor(), "wholesalePriceMinor");
        long rrp = PluginSupport.money(req.rrpMinor(), "rrpMinor");
        String currency = PluginSupport.currency(req.currency());

        InventoryProduct sku = new InventoryProduct();
        sku.id = UUID.randomUUID();
        sku.ownerTenantId = tenantId;
        sku.code = code;
        sku.name = req.name() == null || req.name().isBlank() ? code : req.name().trim();
        sku.unit = req.unit() == null || req.unit().isBlank() ? "EA" : req.unit().trim();
        sku.unitCostMinor = cost;
        sku.wholesalePriceMinor = wholesale;
        sku.rrpMinor = rrp;
        sku.currency = currency;
        sku.status = InventoryProduct.STATUS_PENDING;
        sku.createdBy = personId;
        products.save(sku);

        Map<String, Object> opened = core.openApproval(Map.of(
            "pluginCode", "inventory",
            "action", "SKU_PRICING",
            "subjectType", "SKU",
            "subjectId", sku.id,
            "payload", Map.of("sku", code, "currency", currency,
                "unitCostMinor", Long.toString(cost),
                "wholesalePriceMinor", Long.toString(wholesale),
                "rrpMinor", Long.toString(rrp))), // minor-unit STRINGS on the wire
            assertion, "inv-sku-pricing-" + sku.id);
        sku.openApprovalId = UUID.fromString(String.valueOf(opened.get("approvalId")));
        sku.updatedAt = Instant.now();
        products.save(sku);
        Event.record(events, tenantId, "SKU_CREATED", "SKU", sku.id,
            Map.of("code", code, "approvalId", sku.openApprovalId));
        log.info("sku_created tenantId={} sku={} approvalId={}", tenantId, code, sku.openApprovalId);
        PluginSupport.recordIdempotent(idempotency, personId, idempotencyKey, 200);
        return view(sku);
    }

    /** checker: resolve the open pricing approval; APPROVE → ACTIVE, REJECT → REJECTED. */
    @Transactional
    public InventoryDtos.SkuView decide(UUID tenantId, UUID personId, UUID skuId,
                                        InventoryDtos.DecisionBody body, String assertion) {
        if (body.decision() == null || !ACTIONS.contains(body.decision())) {
            throw ApiProblemException.badRequest("DECISION", "decision must be APPROVE or REJECT");
        }
        InventoryProduct sku = load(tenantId, skuId);
        if (!InventoryProduct.STATUS_PENDING.equals(sku.status) || sku.openApprovalId == null) {
            throw ApiProblemException.conflict("NOT_PENDING", "SKU is not pending a pricing approval");
        }
        core.resolveApproval(sku.openApprovalId, "APPROVE".equals(body.decision()), body.reason(), assertion,
            "inv-sku-decide-" + skuId);
        boolean approve = "APPROVE".equals(body.decision());
        sku.status = approve ? InventoryProduct.STATUS_ACTIVE : InventoryProduct.STATUS_REJECTED;
        sku.approvedBy = personId;
        sku.openApprovalId = null;
        sku.updatedAt = Instant.now();
        products.save(sku);
        Event.record(events, tenantId, approve ? "SKU_PRICED" : "SKU_PRICING_REJECTED", "SKU", sku.id,
            Map.of("by", personId, "decision", body.decision()));
        log.info("sku_decided tenantId={} sku={} decision={}", tenantId, sku.code, body.decision());
        return view(sku);
    }

    // ---- helpers ----

    private InventoryProduct load(UUID tenantId, UUID id) {
        return products.findByIdAndOwnerTenantId(id, tenantId)
            .orElseThrow(() -> ApiProblemException.notFound("SKU_NOT_FOUND", "SKU not found"));
    }

    private InventoryDtos.SkuView view(InventoryProduct p) {
        return new InventoryDtos.SkuView(p.id, p.code, p.name, p.unit,
            Long.toString(p.unitCostMinor), Long.toString(p.wholesalePriceMinor), Long.toString(p.rrpMinor),
            p.currency, p.status, p.openApprovalId,
            String.valueOf(p.createdBy), p.approvedBy == null ? null : String.valueOf(p.approvedBy),
            p.version, p.createdAt, p.updatedAt);
    }
}
