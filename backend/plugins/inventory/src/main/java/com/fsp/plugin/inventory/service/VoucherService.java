package com.fsp.plugin.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fsp.plugin.inventory.api.ApiProblemException;
import com.fsp.plugin.inventory.api.InventoryDtos;
import com.fsp.plugin.inventory.domain.DigitalProduct;
import com.fsp.plugin.inventory.domain.Voucher;
import com.fsp.plugin.inventory.domain.VoucherBatch;
import com.fsp.plugin.inventory.domain.VoucherSale;
import com.fsp.plugin.inventory.repository.DigitalProductRepository;
import com.fsp.plugin.inventory.repository.IdempotencyRecordRepository;
import com.fsp.plugin.inventory.repository.InventoryEventRepository;
import com.fsp.plugin.inventory.repository.VoucherBatchRepository;
import com.fsp.plugin.inventory.repository.VoucherRepository;
import com.fsp.plugin.inventory.repository.VoucherSaleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Digital-plane voucher vault (plans D6/D7, screenplay §D2/D3/D4).
 *  - UPLOAD (maker): plaintext PINs exist ONLY inside this call — sealed at rest with
 *    AES-256-GCM per-batch data keys ({@link VaultCipher}), masked forms everywhere else;
 *  - AUTHORIZE (checker, four-eyes PLUGIN:VOUCHER_RELEASE): pool ACTIVE for sale;
 *  - DISPENSE (single-actor fast path, permission digital.dispense):
 *      TX1  atomic claim AVAILABLE → RESERVED (stripe lock + @Version + partial unique backstop),
 *      NET  journal intent at core — money moves OUTSIDE any lock/tx (AGENTS §8),
 *      TX2  sale row + voucher RESERVED → RELEASED (+ pool EXHAUSTED when drained);
 *    a failed money call re-releases the reservation; a sweeper reclaims ones orphaned by a crash.
 * Money legs (D7): Dr 2050 float face; × splits Cr 2100 telco + 2110 distributor + 4200 platform;
 * the remainder is the merchant margin → the product's margin GL (income; balances the JE).
 */
@Slf4j
@Service
public class VoucherService {

    private static final int GL_FLOAT = 2050;
    private static final int GL_TELCO = 2100;
    private static final int GL_DISTRIBUTOR = 2110;
    private static final int GL_PLATFORM = 4200;

    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "reference", "reference", "productCode", "productCode", "status", "status",
        "createdAt", "createdAt", "authorizedAt", "authorizedAt");

    private final VoucherBatchRepository batches;
    private final VoucherRepository vouchers;
    private final VoucherSaleRepository sales;
    private final DigitalProductRepository products;
    private final InventoryEventRepository events;
    private final IdempotencyRecordRepository idempotency;
    private final CoreClient core;
    private final GlBookResolver glResolver;
    private final VaultCipher vault;
    private final KeyLocks locks;
    private final VoucherTx tx;

    public VoucherService(VoucherBatchRepository batches, VoucherRepository vouchers,
                          VoucherSaleRepository sales, DigitalProductRepository products,
                          InventoryEventRepository events, IdempotencyRecordRepository idempotency,
                          CoreClient core, GlBookResolver glResolver, VaultCipher vault, KeyLocks locks, VoucherTx tx) {
        this.batches = batches;
        this.vouchers = vouchers;
        this.sales = sales;
        this.products = products;
        this.events = events;
        this.idempotency = idempotency;
        this.core = core;
        this.glResolver = glResolver;
        this.vault = vault;
        this.locks = locks;
        this.tx = tx;
    }

    public InventoryDtos.ItemList<InventoryDtos.VoucherBatchView> list(UUID tenantId, int page, int size,
                                                                       String q, String status, String sort) {
        Pageable pageable = Paging.page(SORT_COLUMNS, sort, page, size);
        Page<VoucherBatch> p = batches.search(tenantId, q != null && !q.isBlank(),PluginSupport.searchPattern(q),
            Paging.status(status, VoucherBatch.VALID_STATUSES), pageable);
        return Paging.pageOf(p.map(this::view), size);
    }

    public InventoryDtos.VoucherBatchView get(UUID tenantId, UUID id) {
        return view(load(tenantId, id));
    }

    /** maker upload: seal each PIN; pool enters PENDING_CLEARANCE + PLUGIN:VOUCHER_RELEASE approval. */
    @Transactional
    public InventoryDtos.VoucherBatchView upload(UUID tenantId, UUID personId,
                                                 InventoryDtos.UploadVoucherBatchRequest req,
                                                 String assertion, String idempotencyKey) {
        PluginSupport.requireFresh(idempotency, personId, idempotencyKey);
        String reference = PluginSupport.nonBlank(req.reference(), "reference");
        DigitalProduct product = products.findByIdAndOwnerTenantId(req.digitalProductId(), tenantId)
            .orElseThrow(() -> ApiProblemException.notFound("DIGITAL_PRODUCT_NOT_FOUND", "Digital product not found"));
        if (!DigitalProduct.STATUS_ACTIVE.equals(product.status)) {
            throw ApiProblemException.conflict("PRODUCT_NOT_ACTIVE", "Digital product is not ACTIVE");
        }
        long face = PluginSupport.money(req.unitFaceMinor(), "unitFaceMinor");
        if (face < product.costPriceMinor) {
            throw ApiProblemException.badRequest("FIELD",
                "unitFaceMinor cannot be below the product cost price");
        }
        List<String> pins = req.pins();
        if (pins == null || pins.isEmpty() || pins.size() > 5_000) {
            throw ApiProblemException.badRequest("BOUNDS", "pins must contain 1-5,000 entries");
        }

        VoucherBatch batch = new VoucherBatch();
        batch.id = UUID.randomUUID();
        batch.ownerTenantId = tenantId;
        batch.digitalProductId = product.id;
        batch.productCode = product.code;
        batch.currency = PluginSupport.currency(product.currency);
        batch.unitFaceMinor = face;
        batch.voucherCount = pins.size();
        batch.reference = reference;
        batch.status = VoucherBatch.STATUS_PENDING_CLEARANCE;
        batch.uploadedBy = personId;
        batches.save(batch);

        List<Voucher> rows = new ArrayList<>(pins.size());
        for (String pin : pins) {
            String trimmed = PluginSupport.nonBlank(pin, "pin");
            VaultCipher.Sealed sealed = vault.seal(batch.id, trimmed);
            Voucher v = new Voucher();
            v.id = UUID.randomUUID();
            v.ownerTenantId = tenantId;
            v.batchId = batch.id;
            v.digitalProductId = product.id;
            v.encryptedPin = sealed.ciphertext();
            v.pinNonce = sealed.nonce();
            v.pinTag = sealed.tag();
            v.maskedPin = VaultCipher.mask(trimmed);
            v.faceValueMinor = face;
            v.currency = batch.currency;
            v.status = Voucher.STATUS_AVAILABLE;
            rows.add(v);
        }
        vouchers.saveAll(rows);

        Map<String, Object> opened = core.openApproval(Map.of(
            "pluginCode", "inventory",
            "action", "VOUCHER_RELEASE",
            "subjectType", "VOUCHER_BATCH",
            "subjectId", batch.id,
            "payload", Map.of("product", product.code, "reference", reference,
                "voucherCount", pins.size(), "unitFaceMinor", Long.toString(face),
                "currency", batch.currency)), assertion, "inv-voucher-approval-" + batch.id);
        batch.approvalId = UUID.fromString(String.valueOf(opened.get("approvalId")));
        batches.save(batch);

        Event.record(events, tenantId, "VOUCHER_BATCH_UPLOADED", "VOUCHER_BATCH", batch.id,
            Map.of("product", product.code, "count", pins.size()));
        log.info("voucher_batch_uploaded tenantId={} batch={} product={} count={}",
            tenantId, batch.id, product.code, pins.size());
        PluginSupport.recordIdempotent(idempotency, personId, idempotencyKey, 200);
        return view(batch);
    }

    /** checker authorization: pool ACTIVE for sale (no money moves here). */
    @Transactional
    public InventoryDtos.VoucherBatchView authorize(UUID tenantId, UUID checkerId, UUID batchId,
                                                    InventoryDtos.DecisionBody body, String assertion) {
        if (body.decision() == null || !Set.of("APPROVE", "REJECT").contains(body.decision())) {
            throw ApiProblemException.badRequest("DECISION", "decision must be APPROVE or REJECT");
        }
        VoucherBatch batch = load(tenantId, batchId);
        if (!VoucherBatch.STATUS_PENDING_CLEARANCE.equals(batch.status)) {
            throw ApiProblemException.conflict("NOT_PENDING_CLEARANCE", "Voucher batch is not pending clearance");
        }
        core.resolveApproval(batch.approvalId, "APPROVE".equals(body.decision()), body.reason(), assertion,
            "inv-voucher-authorize-" + batch.id);
        if ("APPROVE".equals(body.decision())) {
            batch.status = VoucherBatch.STATUS_ACTIVE;
            batch.authorizedBy = checkerId;
            batch.authorizedAt = Instant.now();
        } else {
            batch.status = VoucherBatch.STATUS_EXHAUSTED; // withdrawn from sale; never ACTIVE
        }
        batches.save(batch);
        Event.record(events, tenantId,
            "APPROVE".equals(body.decision()) ? "VOUCHER_BATCH_AUTHORIZED" : "VOUCHER_BATCH_WITHDRAWN",
            "VOUCHER_BATCH", batch.id, Map.of("by", checkerId));
        log.info("voucher_batch_authorized tenantId={} batch={} decision={}", tenantId, batchId, body.decision());
        return view(batch);
    }

    /**
     * Dispense. TX1 (claim) and TX2 (release) are separate short transactions; the core money
     * call runs between them with NO lock or transaction held (AGENTS §8).
     */
    public InventoryDtos.DispenseView dispense(UUID tenantId, UUID personId,
                                               InventoryDtos.DispenseRequest req, String assertion) {
        String saleRef = PluginSupport.nonBlank(req.saleReference(), "saleReference");
        String outKey = "inv-dispense-je-" + tenantId + "-" + saleRef;
        PluginSupport.requireFresh(idempotency, personId, "inv-dispense-" + tenantId + "-" + saleRef);

        DigitalProduct product = products.findByIdAndOwnerTenantId(req.digitalProductId(), tenantId)
            .orElseThrow(() -> ApiProblemException.notFound("DIGITAL_PRODUCT_NOT_FOUND", "Digital product not found"));
        if (!DigitalProduct.STATUS_ACTIVE.equals(product.status)) {
            throw ApiProblemException.conflict("PRODUCT_NOT_ACTIVE", "Digital product is not ACTIVE");
        }

        // ---- TX1: atomic claim, FIFO across pools (sorted ids; AGENTS §2d) ----
        List<VoucherBatch> pools = batches.search(tenantId, false, null, VoucherBatch.STATUS_ACTIVE,
            org.springframework.data.domain.PageRequest.of(0, 50,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "createdAt")))
            .getContent();
        if (pools.isEmpty()) {
            throw ApiProblemException.conflict("NO_VOUCHER_POOL", "No authorized voucher pool has stock");
        }
        Claimed claimed = locks.withLock("voucher-release", tenantId, () -> claim(tenantId, pools));

        long telco = Split.telco(product.salePriceMinor, product.telcoBp, product.distributorBp, product.platformBp);
        long distributor = Split.distributor(product.salePriceMinor, product.distributorBp);
        long platform = Split.platform(product.salePriceMinor, product.platformBp);
        long margin = product.salePriceMinor - telco - distributor - platform; // remainder → balances the JE
        long amount = product.salePriceMinor;

        try {
            // ---- NET: journal intent in core (single actor; planner + TigerBeetle in core ONLY) ----
            Map<Integer, GlBookResolver.Resolved> gls = glResolver.gls(tenantId,
                GL_FLOAT, GL_TELCO, GL_DISTRIBUTOR, GL_PLATFORM, product.marginGlCode);
            JsonNode entry = core.postIntent(tenantId, Map.of(
                "bookId", gls.get(GL_FLOAT).bookId().toString(),
                "entryType", "INVENTORY",
                "description", "Voucher dispense " + product.code + " (" + saleRef + ")",
                "reference", "INV-VOUCHER-" + saleRef,
                "postingDate", LocalDate.now().toString(),
                "lines", List.of(
                    Map.of("glAccountId", gls.get(GL_FLOAT).glAccountId().toString(),
                        "direction", "DEBIT", "amountMinor", Long.toString(amount),
                        "memo", "Digital float drawdown " + product.code),
                    Map.of("glAccountId", gls.get(GL_TELCO).glAccountId().toString(),
                        "direction", "CREDIT", "amountMinor", Long.toString(telco),
                        "memo", "Telco share " + product.code),
                    Map.of("glAccountId", gls.get(GL_DISTRIBUTOR).glAccountId().toString(),
                        "direction", "CREDIT", "amountMinor", Long.toString(distributor),
                        "memo", "Distributor commission"),
                    Map.of("glAccountId", gls.get(GL_PLATFORM).glAccountId().toString(),
                        "direction", "CREDIT", "amountMinor", Long.toString(platform),
                        "memo", "Platform fee"),
                    Map.of("glAccountId", gls.get(product.marginGlCode).glAccountId().toString(),
                        "direction", "CREDIT", "amountMinor", Long.toString(margin),
                        "memo", "Merchant voucher margin"))), assertion, outKey);
            UUID entryId = UUID.fromString(entry.path("entryId").asText());
            String jeStatus = entry.path("status").asText("");
            if (!"FINALIZED".equals(jeStatus)) {
                // the money is uncertain (core's check-status must resolve it first) — do NOT release
                throw new ApiProblemException("JE_NOT_FINALIZED",
                    "Journal intent " + entryId + " is not finalized yet (" + jeStatus + "); retry", 503);
            }

            // ---- TX2: commit the sale (only if the money call above succeeded) ----
            UUID saleId = tx.commitSale(tenantId, personId, saleRef, req.channel(), product.id,
                claimed.batch().id, claimed.voucher().id, amount, entryId);
            Event.record(events, tenantId, "VOUCHER_DISPENSED", "VOUCHER", claimed.voucher().id,
                Map.of("sale", saleRef, "journalEntryId", entryId, "by", personId));
            log.info("voucher_dispensed tenantId={} sale={} entryId={}", tenantId, saleRef, entryId);
            PluginSupport.recordIdempotent(idempotency, personId, "inv-dispense-" + tenantId + "-" + saleRef, 200);
            // the PIN leaves the vault exactly once, opened for THIS response — never logged (D6)
            String pin = vault.open(claimed.voucher().batchId, claimed.voucher().encryptedPin,
                claimed.voucher().pinNonce, claimed.voucher().pinTag);
            return new InventoryDtos.DispenseView(saleId, product.id, product.code, claimed.voucher().id,
                claimed.voucher().maskedPin, pin, Long.toString(amount),
                Long.toString(claimed.voucher().faceValueMinor), product.currency,
                entryId, Instant.now());
        } catch (ApiProblemException e) {
            if (!"JE_NOT_FINALIZED".equals(e.getCode())) {
                // any failed/refused money call → return the reservation to the pool (best effort)
                rollbackQuietly(claimed);
            }
            throw e;
        } catch (RuntimeException e) {
            rollbackQuietly(claimed);
            throw e;
        }
    }

    private void rollbackQuietly(Claimed claimed) {
        try {
            tx.rollbackClaim(claimed.voucher().id);
        } catch (Exception rollback) {
            log.warn("voucher_claim_rollback_failed tenantId={} voucher={} — the sweeper will reclaim it",
                    claimed.batch().ownerTenantId, claimed.voucher().id);
        }
    }

    /** Sweeper: reservations orphaned by a crashed process return to the pool after a grace period
     *  (only when no committed sale references them — the reservation never got released). */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    @Transactional
    public void reclaimExpiredReservations() {
        var orphans = vouchers.findByStatusAndReservedAtBefore(Voucher.STATUS_RESERVED,
            Instant.now().minusSeconds(120), org.springframework.data.domain.PageRequest.of(0, 200));
        for (Voucher v : orphans) {
            if (sales.findByVoucherId(v.id).isPresent() && Voucher.STATUS_RESERVED.equals(v.status)
                && v.releaseSaleId == null) {
                // a sale row exists but release never landed → keep for manual reconciliation
                log.error("voucher_sale_without_release voucher={} — manual reconciliation required", v.id);
                continue;
            }
            Voucher fresh = vouchers.findById(v.id).orElseThrow();
            if (Voucher.STATUS_RESERVED.equals(fresh.status) && fresh.releaseSaleId == null
                && fresh.reservedAt != null && fresh.reservedAt.isBefore(Instant.now().minusSeconds(120))) {
                fresh.status = Voucher.STATUS_AVAILABLE;
                fresh.reservedAt = null;
                vouchers.save(fresh);
                log.warn("voucher_reservation_reclaimed voucher={} batch={}", fresh.id, fresh.batchId);
            }
        }
    }

    // ---- helpers ----

    private record Claimed(VoucherBatch batch, Voucher voucher) {}

    /** Must run UNDER the stripe lock; DB preconditions (@Version) stay the cross-instance backstop. */
    private Claimed claim(UUID tenantId, List<VoucherBatch> pools) {
        for (VoucherBatch pool : pools) {
            if (!VoucherBatch.STATUS_ACTIVE.equals(pool.status)) continue;
            var opt = vouchers.findFirstByOwnerTenantIdAndBatchIdAndStatusOrderByIdAsc(
                tenantId, pool.id, Voucher.STATUS_AVAILABLE);
            if (opt.isEmpty()) continue;
            Voucher voucher = opt.get();
            voucher.status = Voucher.STATUS_RESERVED;
            voucher.reservedAt = Instant.now();
            vouchers.save(voucher);
            return new Claimed(pool, voucher);
        }
        throw ApiProblemException.conflict("POOL_EMPTY", "All voucher pools are exhausted");
    }

    private VoucherBatch load(UUID tenantId, UUID id) {
        return batches.findByIdAndOwnerTenantId(id, tenantId)
            .orElseThrow(() -> ApiProblemException.notFound("VOUCHER_BATCH_NOT_FOUND", "Voucher batch not found"));
    }

    private InventoryDtos.VoucherBatchView view(VoucherBatch b) {
        return new InventoryDtos.VoucherBatchView(b.id, b.digitalProductId, b.productCode, b.currency,
            Long.toString(b.unitFaceMinor), b.voucherCount, b.reference, b.status, b.approvalId,
            vouchers.countByBatchIdAndStatus(b.id, Voucher.STATUS_AVAILABLE),
            String.valueOf(b.uploadedBy), b.authorizedBy == null ? null : String.valueOf(b.authorizedBy),
            b.version, b.createdAt, b.authorizedAt);
    }
}
