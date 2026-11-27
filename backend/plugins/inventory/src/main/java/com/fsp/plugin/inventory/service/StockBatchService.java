package com.fsp.plugin.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fsp.plugin.inventory.api.ApiProblemException;
import com.fsp.plugin.inventory.api.InventoryDtos;
import com.fsp.plugin.inventory.domain.InventoryEvent;
import com.fsp.plugin.inventory.domain.InventoryProduct;
import com.fsp.plugin.inventory.domain.InventoryStockBatch;
import com.fsp.plugin.inventory.domain.InventoryStockMovement;
import com.fsp.plugin.inventory.repository.IdempotencyRecordRepository;
import com.fsp.plugin.inventory.repository.InventoryEventRepository;
import com.fsp.plugin.inventory.repository.InventoryProductRepository;
import com.fsp.plugin.inventory.repository.InventoryStockBatchRepository;
import com.fsp.plugin.inventory.repository.InventoryStockMovementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stock receipts (physical plane) — the four-eyes money saga (plans D2/D3, screenplay §P2/P5).
 * 1. maker stages the batch AND the JE draft (Dr 1200 Inventory On Hand / Cr 2010 Goods Received
 *    Clearing), submitted on-behalf of the maker → JE PENDING_CHECKER + PLUGIN:STOCK_RECEIPT approval;
 * 2. checker decides: approve resolves the JE approval in core (JE posts FINALIZED — TigerBeetle
 *    updated by CORE ONLY) → batch COMMITTED + one RECEIPT movement; reject → batch REJECTED.
 * Deterministic outbound idempotency keys (AGENTS §9.1) keyed by the client batch reference:
 * a retry that crashed mid-saga reuses the SAME draft/approval instead of duplicating money.
 */
@Slf4j
@Service
public class StockBatchService {

    private static final int GL_INVENTORY = 1200;
    private static final int GL_CLEARING = 2010;

    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "reference", "reference", "skuCode", "skuCode", "status", "status",
        "receivedAt", "receivedAt", "decidedAt", "decidedAt");

    private final InventoryStockBatchRepository batches;
    private final InventoryProductRepository products;
    private final InventoryStockMovementRepository movements;
    private final InventoryEventRepository events;
    private final IdempotencyRecordRepository idempotency;
    private final CoreClient core;
    private final GlBookResolver glResolver;

    public StockBatchService(InventoryStockBatchRepository batches, InventoryProductRepository products,
                             InventoryStockMovementRepository movements, InventoryEventRepository events,
                             IdempotencyRecordRepository idempotency, CoreClient core, GlBookResolver glResolver) {
        this.batches = batches;
        this.products = products;
        this.movements = movements;
        this.events = events;
        this.idempotency = idempotency;
        this.core = core;
        this.glResolver = glResolver;
    }

    public InventoryDtos.ItemList<InventoryDtos.StockBatchView> list(UUID tenantId, int page, int size,
                                                                     String q, String status, String sort) {
        Pageable pageable = Paging.page(SORT_COLUMNS, sort, page, size);
        Page<InventoryStockBatch> p = batches.search(tenantId, q != null && !q.isBlank(),PluginSupport.searchPattern(q),
            Paging.status(status, InventoryStockBatch.VALID_STATUSES), pageable);
        return Paging.pageOf(p.map(this::view), size);
    }

    public InventoryDtos.StockBatchView get(UUID tenantId, UUID id) {
        return view(load(tenantId, id));
    }

    public List<InventoryDtos.StockMovementView> movements(UUID tenantId, UUID batchId) {
        load(tenantId, batchId); // tenant scope check
        return movements.findByBatchIdAndOwnerTenantIdOrderByMovedAtDesc(batchId, tenantId).stream()
            .limit(100)
            .map(m -> new InventoryDtos.StockMovementView(m.id, m.batchId, m.productId, m.direction,
                m.quantity, m.reference, m.actorId, m.movedAt))
            .toList();
    }

    /** maker stage (idempotent on the client Idempotency-Key; deterministic JE/approval keys). */
    @Transactional
    public InventoryDtos.StockBatchView stage(UUID tenantId, UUID personId, String username,
                                              InventoryDtos.CreateStockBatchRequest req,
                                              String assertion, String idempotencyKey) {
        PluginSupport.requireFresh(idempotency, personId, idempotencyKey);
        String reference = PluginSupport.nonBlank(req.reference(), "reference");
        if (req.quantity() <= 0 || req.quantity() > 1_000_000) {
            throw ApiProblemException.badRequest("FIELD", "quantity must be 1-1,000,000");
        }
        long unitCost = PluginSupport.money(req.unitCostMinor(), "unitCostMinor");
        if (unitCost == 0) throw ApiProblemException.badRequest("FIELD", "unitCostMinor must be > 0");
        long total = unitCost * req.quantity();
        if (total / req.quantity() != unitCost) {
            throw ApiProblemException.badRequest("FIELD", "batch cost overflows long minor units");
        }

        InventoryProduct sku = products.findByIdAndOwnerTenantId(req.productId(), tenantId)
            .orElseThrow(() -> ApiProblemException.notFound("SKU_NOT_FOUND", "SKU not found"));
        if (!InventoryProduct.STATUS_ACTIVE.equals(sku.status)) {
            throw ApiProblemException.conflict("SKU_NOT_ACTIVE", "Only ACTIVE SKUs can receive stock");
        }

        var ledger = glResolver.refs(tenantId);
        String currency = PluginSupport.currency(sku.currency);
        if (!ledger.currency().equalsIgnoreCase(currency)) {
            throw ApiProblemException.conflict("CURRENCY_MISMATCH",
                "SKU currency " + currency + " != book currency " + ledger.currency());
        }
        glResolver.gls(tenantId, GL_INVENTORY, GL_CLEARING); // must exist + be provisioned

        InventoryStockBatch batch = new InventoryStockBatch();
        batch.id = UUID.randomUUID();
        batch.ownerTenantId = tenantId;
        batch.productId = sku.id;
        batch.skuCode = sku.code;
        batch.skuName = sku.name;
        batch.currency = currency;
        batch.quantity = req.quantity();
        batch.unitCostMinor = unitCost;
        batch.totalCostMinor = total;
        batch.reference = reference;
        batch.note = req.note();
        batch.status = InventoryStockBatch.STATUS_STAGED;
        batch.receivedBy = personId;

        // ---- JE draft as the maker (balanced; core re-checks every invariant) ----
        String createKey = "inv-receipt-je-create-" + tenantId + "-" + reference;
        JsonNode entry = core.createJeDraft(tenantId, Map.of(
            "bookId", ledger.bookId().toString(),
            "entryType", "INVENTORY",
            "description", "Inventory receipt " + sku.code + " (" + reference + ")",
            "reference", "INV-RECEIPT-" + reference,
            "postingDate", LocalDate.now().toString(),
            "lines", List.of(
                Map.of("glAccountId", glResolver.gl(tenantId, GL_INVENTORY).glAccountId().toString(),
                    "direction", "DEBIT", "amountMinor", Long.toString(total),
                    "memo", "Receipt of " + req.quantity() + " " + sku.code),
                Map.of("glAccountId", glResolver.gl(tenantId, GL_CLEARING).glAccountId().toString(),
                    "direction", "CREDIT", "amountMinor", Long.toString(total),
                    "memo", "Goods received clearing"))), assertion, createKey);
        UUID entryId = UUID.fromString(entry.path("entryId").asText());
        batch.journalEntryId = entryId;

        // ---- submit as the maker → PENDING_CHECKER (tolerant of a prior partial run) ----
        submitTolerant(tenantId, entryId, assertion);

        // ---- plugin approval PLUGIN:STOCK_RECEIPT (maker = person; core re-checks maker≠checker) ----
        String approvalKey = "inv-receipt-approval-" + tenantId + "-" + reference;
        Map<String, Object> opened = core.openApproval(Map.of(
            "pluginCode", "inventory",
            "action", "STOCK_RECEIPT",
            "subjectType", "STOCK_BATCH",
            "subjectId", batch.id,
            "payload", Map.of("sku", sku.code, "reference", reference, "quantity", req.quantity(),
                "currency", currency, "totalCostMinor", Long.toString(total),
                "journalEntryId", entryId.toString())), assertion, approvalKey);
        batch.approvalId = UUID.fromString(String.valueOf(opened.get("approvalId")));

        batches.save(batch);
        Event.record(events, tenantId, "STOCK_BATCH_STAGED", "STOCK_BATCH", batch.id,
            Map.of("sku", sku.code, "quantity", req.quantity(), "journalEntryId", entryId));
        log.info("stock_batch_staged tenantId={} batch={} sku={} entryId={} approvalId={}",
            tenantId, batch.id, sku.code, entryId, batch.approvalId);
        PluginSupport.recordIdempotent(idempotency, personId, idempotencyKey, 200);
        return view(batch);
    }

    /** checker decision; approves resolve the JE in core (money moves ONLY after core posts it). */
    @Transactional
    public InventoryDtos.StockBatchView decide(UUID tenantId, UUID checkerId, UUID batchId,
                                               InventoryDtos.DecisionBody body, String assertion) {
        if (body.decision() == null || !Set.of("APPROVE", "REJECT").contains(body.decision())) {
            throw ApiProblemException.badRequest("DECISION", "decision must be APPROVE or REJECT");
        }
        InventoryStockBatch batch = load(tenantId, batchId);
        if (!InventoryStockBatch.STATUS_STAGED.equals(batch.status)) {
            throw ApiProblemException.conflict("NOT_STAGED", "Batch is not STAGED");
        }
        if ("APPROVE".equals(body.decision())) {
            // core posts the JE (four-eyes maker ≠ checker enforced there at person_id)
            approveJeTolerant(tenantId, batch.journalEntryId, assertion);
            batch.status = InventoryStockBatch.STATUS_COMMITTED;
            batch.approvedBy = checkerId;
            batch.decidedAt = Instant.now();
            saveMovement(tenantId, batch, checkerId);
            Event.record(events, tenantId, "STOCK_BATCH_COMMITTED", "STOCK_BATCH", batch.id,
                Map.of("by", checkerId, "journalEntryId", batch.journalEntryId));
            log.info("stock_batch_committed tenantId={} batch={} qty={}", tenantId, batch.id, batch.quantity);
        } else {
            core.resolveApproval(batch.approvalId, false, body.reason(), assertion,
                "inv-receipt-reject-" + batch.id);
            batch.status = InventoryStockBatch.STATUS_REJECTED;
            batch.approvedBy = checkerId;
            batch.decidedAt = Instant.now();
            Event.record(events, tenantId, "STOCK_BATCH_REJECTED", "STOCK_BATCH", batch.id,
                Map.of("by", checkerId, "reason", body.reason() == null ? "" : body.reason()));
            log.info("stock_batch_rejected tenantId={} batch={}", tenantId, batch.id);
        }
        batches.save(batch);
        return view(batch);
    }

    // ---- helpers ----

    private void submitTolerant(UUID tenantId, UUID entryId, String assertion) {
        try {
            core.submitJe(tenantId, entryId, assertion);
        } catch (ApiProblemException e) {
            if (!"JE_STATE".equals(e.getCode())) throw e;
            // already submitted by a crashed attempt: only PENDING_CHECKER/FINALIZED are acceptable
            String status = entryStatus(tenantId, entryId);
            if (!("PENDING_CHECKER".equals(status) || "FINALIZED".equals(status))) {
                throw new ApiProblemException("JE_STATE", "JE " + entryId + " is " + status, 409);
            }
        }
    }

    private void approveJeTolerant(UUID tenantId, UUID entryId, String assertion) {
        try {
            core.approveJe(tenantId, entryId, assertion);
        } catch (ApiProblemException e) {
            if ("MAKER_NOT_CHECKER".equals(e.getCode())) throw e;
            if (!"JE_STATE".equals(e.getCode())) throw e;
            // already posted by a crashed attempt: only FINALIZED is acceptable
            String status = entryStatus(tenantId, entryId);
            if (!"FINALIZED".equals(status)) {
                throw new ApiProblemException("JE_STATE", "JE " + entryId + " is " + status, 409);
            }
        }
    }

    private String entryStatus(UUID tenantId, UUID entryId) {
        try {
            JsonNode entry = core.getJeEntry(tenantId, entryId, null);
            String status = entry.path("status").asText("");
            if (status.isBlank()) {
                throw new ApiProblemException("JE_MISSING", "Journal entry " + entryId + " not found", 409);
            }
            return status;
        } catch (ApiProblemException e) {
            throw e;
        }
    }

    private void saveMovement(UUID tenantId, InventoryStockBatch batch, UUID actorId) {
        InventoryStockMovement m = new InventoryStockMovement();
        m.id = UUID.randomUUID();
        m.ownerTenantId = tenantId;
        m.batchId = batch.id;
        m.productId = batch.productId;
        m.direction = InventoryStockMovement.RECEIPT;
        m.quantity = batch.quantity;
        m.reference = batch.reference;
        m.actorId = actorId;
        movements.save(m);
    }

    private InventoryStockBatch load(UUID tenantId, UUID id) {
        return batches.findByIdAndOwnerTenantId(id, tenantId)
            .orElseThrow(() -> ApiProblemException.notFound("STOCK_BATCH_NOT_FOUND", "Stock batch not found"));
    }

    private InventoryDtos.StockBatchView view(InventoryStockBatch b) {
        return new InventoryDtos.StockBatchView(b.id, b.productId, b.skuCode, b.skuName,
            b.currency, b.quantity, Long.toString(b.unitCostMinor), Long.toString(b.totalCostMinor),
            b.reference, b.note, b.status, b.journalEntryId, b.approvalId,
            String.valueOf(b.receivedBy), b.approvedBy == null ? null : String.valueOf(b.approvedBy),
            b.version, b.receivedAt, b.decidedAt);
    }
}
