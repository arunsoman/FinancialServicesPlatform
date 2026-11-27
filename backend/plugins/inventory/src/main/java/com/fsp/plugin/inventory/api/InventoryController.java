package com.fsp.plugin.inventory.api;

import com.fsp.plugin.inventory.security.ActorAssertionFilter;
import com.fsp.plugin.inventory.service.DigitalProductService;
import com.fsp.plugin.inventory.service.FloatService;
import com.fsp.plugin.inventory.service.InventorySkuService;
import com.fsp.plugin.inventory.service.StockBatchService;
import com.fsp.plugin.inventory.service.VoucherService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Inventory plugin API — manifest ops ONLY (plan §5 / decisions D8/D10). Every request is
 * reached through the gateway; the caller is the verified actor in
 * {@link ActorAssertionFilter} (never a header), scope-check per tenant. Core decided the
 * permission BEFORE the request got here; the plugin re-checks domain preconditions on
 * database truth. Money: SKU/stock/float go through four-eyes JEs, dispense through the
 * single-actor journal intent — ALL posted by core only.
 */
@Slf4j
@RestController
@RequestMapping("/v1/plugins/inventory")
public class InventoryController {

    private final InventorySkuService skuService;
    private final StockBatchService stockService;
    private final DigitalProductService digitalService;
    private final VoucherService voucherService;
    private final FloatService floatService;

    public InventoryController(InventorySkuService skuService, StockBatchService stockService,
                               DigitalProductService digitalService, VoucherService voucherService,
                               FloatService floatService) {
        this.skuService = skuService;
        this.stockService = stockService;
        this.digitalService = digitalService;
        this.voucherService = voucherService;
        this.floatService = floatService;
    }

    // ---------- SKUs (physical) ----------

    /** inventory.sku.list — GET /skus?page&size&q&status&sort */
    @GetMapping("/skus")
    public InventoryDtos.ItemList<InventoryDtos.SkuView> listSkus(HttpServletRequest request,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort) {
        var actor = ActorAssertionFilter.actor(request);
        return skuService.list(actor.tenantId(), page, size, q, status, sort);
    }

    /** inventory.sku.get — GET /skus/{skuId} */
    @GetMapping("/skus/{skuId}")
    public InventoryDtos.SkuView getSku(HttpServletRequest request, @PathVariable("skuId") UUID skuId) {
        var actor = ActorAssertionFilter.actor(request);
        return skuService.get(actor.tenantId(), skuId);
    }

    /** inventory.sku.create — POST /skus (opens PLUGIN:SKU_PRICING four-eyes approval) */
    @PostMapping("/skus")
    public ResponseEntity<InventoryDtos.SkuView> createSku(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InventoryDtos.CreateSkuRequest body) {
        var actor = ActorAssertionFilter.actor(request);
        InventoryDtos.SkuView view = skuService.create(actor.tenantId(), actor.personId(),
            body, actor.assertion(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** inventory.sku.decide — POST /skus/{skuId}/pricing-decision (checker; activates the SKU) */
    @PostMapping("/skus/{skuId}/pricing-decision")
    public InventoryDtos.SkuView decideSku(HttpServletRequest request,
            @PathVariable("skuId") UUID skuId,
            @RequestBody InventoryDtos.DecisionBody body) {
        var actor = ActorAssertionFilter.actor(request);
        return skuService.decide(actor.tenantId(), actor.personId(), skuId, body, actor.assertion());
    }

    // ---------- stock (physical) ----------

    /** inventory.stock.batch.list — GET /stock-batches */
    @GetMapping("/stock-batches")
    public InventoryDtos.ItemList<InventoryDtos.StockBatchView> listBatches(HttpServletRequest request,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort) {
        var actor = ActorAssertionFilter.actor(request);
        return stockService.list(actor.tenantId(), page, size, q, status, sort);
    }

    /** inventory.stock.batch.get — GET /stock-batches/{batchId} */
    @GetMapping("/stock-batches/{batchId}")
    public InventoryDtos.StockBatchView getBatch(HttpServletRequest request, @PathVariable("batchId") UUID batchId) {
        var actor = ActorAssertionFilter.actor(request);
        return stockService.get(actor.tenantId(), batchId);
    }

    /** inventory.stock.batch.create — POST /stock-batches ( maker; four-eyes PLUGIN:STOCK_RECEIPT + JE Dr 1200/Cr 2010) */
    @PostMapping("/stock-batches")
    public ResponseEntity<InventoryDtos.StockBatchView> createBatch(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InventoryDtos.CreateStockBatchRequest body) {
        var actor = ActorAssertionFilter.actor(request);
        InventoryDtos.StockBatchView view = stockService.stage(actor.tenantId(), actor.personId(),
            actor.username(), body, actor.assertion(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** inventory.stock.batch.decide — POST /stock-batches/{batchId}/decision (checker; core posts the JE) */
    @PostMapping("/stock-batches/{batchId}/decision")
    public InventoryDtos.StockBatchView decideBatch(HttpServletRequest request,
            @PathVariable("batchId") UUID batchId,
            @RequestBody InventoryDtos.DecisionBody body) {
        var actor = ActorAssertionFilter.actor(request);
        return stockService.decide(actor.tenantId(), actor.personId(), batchId, body, actor.assertion());
    }

    /** Historical movements (event trail read model) — GET /stock-batches/{batchId}/movements */
    @GetMapping("/stock-batches/{batchId}/movements")
    public InventoryDtos.ItemList<InventoryDtos.StockMovementView> movements(HttpServletRequest request,
            @PathVariable("batchId") UUID batchId) {
        var actor = ActorAssertionFilter.actor(request);
        var movements = stockService.movements(actor.tenantId(), batchId);
        return new InventoryDtos.ItemList<>(movements, false, null, "movedAt,desc");
    }

    // ---------- digital products ----------

    /** inventory.digital.product.list — GET /digital-products */
    @GetMapping("/digital-products")
    public InventoryDtos.ItemList<InventoryDtos.DigitalProductView> listDigitalProducts(HttpServletRequest request,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort) {
        var actor = ActorAssertionFilter.actor(request);
        return digitalService.list(actor.tenantId(), page, size, q, status, sort);
    }

    /** inventory.digital.product.create — POST /digital-products (no approval; margin GL must exist) */
    @PostMapping("/digital-products")
    public ResponseEntity<InventoryDtos.DigitalProductView> createDigitalProduct(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InventoryDtos.CreateDigitalProductRequest body) {
        var actor = ActorAssertionFilter.actor(request);
        InventoryDtos.DigitalProductView view = digitalService.create(actor.tenantId(), actor.personId(),
            body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    // ---------- voucher vault (digital) ----------

    /** inventory.voucher.batch.list — GET /voucher-batches */
    @GetMapping("/voucher-batches")
    public InventoryDtos.ItemList<InventoryDtos.VoucherBatchView> listVoucherBatches(HttpServletRequest request,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort) {
        var actor = ActorAssertionFilter.actor(request);
        return voucherService.list(actor.tenantId(), page, size, q, status, sort);
    }

    /** inventory.voucher.batch.get — GET /voucher-batches/{batchId} */
    @GetMapping("/voucher-batches/{batchId}")
    public InventoryDtos.VoucherBatchView getVoucherBatch(HttpServletRequest request, @PathVariable("batchId") UUID batchId) {
        var actor = ActorAssertionFilter.actor(request);
        return voucherService.get(actor.tenantId(), batchId);
    }

    /** inventory.voucher.batch.upload — POST /voucher-batches ( maker; PINs sealed with AES-256-GCM, D6) */
    @PostMapping("/voucher-batches")
    public ResponseEntity<InventoryDtos.VoucherBatchView> uploadVoucherBatch(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InventoryDtos.UploadVoucherBatchRequest body) {
        var actor = ActorAssertionFilter.actor(request);
        InventoryDtos.VoucherBatchView view = voucherService.upload(actor.tenantId(), actor.personId(),
            body, actor.assertion(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** inventory.voucher.batch.authorize — POST /voucher-batches/{batchId}/authorization (checker; four-eyes) */
    @PostMapping("/voucher-batches/{batchId}/authorization")
    public InventoryDtos.VoucherBatchView authorizeVoucherBatch(HttpServletRequest request,
            @PathVariable("batchId") UUID batchId,
            @RequestBody InventoryDtos.DecisionBody body) {
        var actor = ActorAssertionFilter.actor(request);
        return voucherService.authorize(actor.tenantId(), actor.personId(), batchId, body, actor.assertion());
    }

    /** inventory.voucher.dispense — POST /vouchers/dispense (single actor; journal intent at core; PIN released ONCE) */
    @PostMapping("/vouchers/dispense")
    public InventoryDtos.DispenseView dispense(HttpServletRequest request,
            @RequestBody InventoryDtos.DispenseRequest body) {
        var actor = ActorAssertionFilter.actor(request);
        return voucherService.dispense(actor.tenantId(), actor.personId(), body, actor.assertion());
    }

    // ---------- float funding ----------

    /** inventory.float.deposit.list — GET /float-deposits */
    @GetMapping("/float-deposits")
    public InventoryDtos.ItemList<InventoryDtos.FloatDepositView> listFloatDeposits(HttpServletRequest request,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort) {
        var actor = ActorAssertionFilter.actor(request);
        return floatService.list(actor.tenantId(), page, size, q, status, sort);
    }

    /** inventory.float.deposit.create — POST /float-deposits ( maker; four-eyes PLUGIN:FLOAT_FUNDING + JE Dr 1000/Cr 2050) */
    @PostMapping("/float-deposits")
    public ResponseEntity<InventoryDtos.FloatDepositView> createFloatDeposit(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InventoryDtos.CreateFloatDepositRequest body) {
        var actor = ActorAssertionFilter.actor(request);
        InventoryDtos.FloatDepositView view = floatService.deposit(actor.tenantId(), actor.personId(),
            body, actor.assertion(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** inventory.float.deposit.decide — POST /float-deposits/{depositId}/decision (checker; core posts the JE) */
    @PostMapping("/float-deposits/{depositId}/decision")
    public InventoryDtos.FloatDepositView decideFloatDeposit(HttpServletRequest request,
            @PathVariable("depositId") UUID depositId,
            @RequestBody InventoryDtos.DecisionBody body) {
        var actor = ActorAssertionFilter.actor(request);
        return floatService.decide(actor.tenantId(), actor.personId(), depositId, body, actor.assertion());
    }
}
