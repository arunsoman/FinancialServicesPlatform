package com.fsp.plugin.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire DTOs. Minor-unit amounts travel as STRINGS (JS-safe) — plan §12 / AGENTS §2b. */
public final class InventoryDtos {

    private InventoryDtos() {}

    // ---------- SKUs ----------

    public record CreateSkuRequest(String code, String name, String unit,
                                   String unitCostMinor, String wholesalePriceMinor, String rrpMinor,
                                   String currency) {}

    public record SkuView(UUID id, String code, String name, String unit,
                          String unitCostMinor, String wholesalePriceMinor, String rrpMinor,
                          String currency, String status, UUID openApprovalId,
                          String createdBy, String approvedBy, long version,
                          Instant createdAt, Instant updatedAt) {}

    public record DecisionBody(String decision /* APPROVE|REJECT */, String reason) {}

    public record ItemList<T>(List<T> items, boolean hasMore, String nextCursor, String sort) {}

    // ---------- stock ----------

    public record CreateStockBatchRequest(UUID productId, int quantity, String unitCostMinor,
                                          String reference, String note) {}

    public record StockBatchView(UUID id, UUID productId, String skuCode, String skuName,
                                 String currency, int quantity, String unitCostMinor,
                                 String totalCostMinor, String reference, String note,
                                 String status, UUID journalEntryId, UUID approvalId,
                                 String receivedBy, String approvedBy, long version,
                                 Instant receivedAt, Instant decidedAt) {}

    public record StockMovementView(UUID id, UUID batchId, UUID productId, String direction,
                                    int quantity, String reference, UUID actorId, Instant movedAt) {}

    // ---------- digital ----------

    public record CreateDigitalProductRequest(String code, String name, String salePriceMinor,
                                              String costPriceMinor, String currency,
                                              Integer telcoBp, Integer distributorBp, Integer platformBp,
                                              Integer marginGlCode) {}

    public record DigitalProductView(UUID id, String code, String name, String kind, String status,
                                     String salePriceMinor, String costPriceMinor, String currency,
                                     int telcoBp, int distributorBp, int platformBp,
                                     int marginGlCode, long version, Instant createdAt) {}

    public record UploadVoucherBatchRequest(UUID digitalProductId, String unitFaceMinor,
                                            String reference, List<String> pins) {}

    public record VoucherBatchView(UUID id, UUID digitalProductId, String productCode, String currency,
                                   String unitFaceMinor, int voucherCount, String reference,
                                   String status, UUID approvalId, int availableCount,
                                   String uploadedBy, String authorizedBy, long version,
                                   Instant createdAt, Instant authorizedAt) {}

    public record DispenseRequest(UUID digitalProductId, String saleReference, String channel,
                                  String customerRef) {}

    public record DispenseView(UUID saleId, UUID digitalProductId, String productCode, UUID voucherId,
                               String productIdPinMask, String pin /* released ONCE — plan D6 */,
                               String salePriceMinor, String faceMinor, String currency,
                               UUID journalEntryId, Instant dispensedAt) {}

    // ---------- float ----------

    public record CreateFloatDepositRequest(String floatCode, String amountMinor, String currency, String note) {}

    public record FloatDepositView(UUID id, String floatCode, String currency, String amountMinor,
                                   String note, String status, UUID journalEntryId, UUID approvalId,
                                   String requestedBy, String approvedBy, long version,
                                   Instant createdAt, Instant decidedAt) {}

    public record ReadinessView(List<String> gates) {}
}
