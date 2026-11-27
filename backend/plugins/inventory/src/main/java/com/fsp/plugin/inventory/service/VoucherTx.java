package com.fsp.plugin.inventory.service;

import com.fsp.plugin.inventory.api.ApiProblemException;
import com.fsp.plugin.inventory.domain.Voucher;
import com.fsp.plugin.inventory.domain.VoucherBatch;
import com.fsp.plugin.inventory.domain.VoucherSale;
import com.fsp.plugin.inventory.repository.VoucherBatchRepository;
import com.fsp.plugin.inventory.repository.VoucherRepository;
import com.fsp.plugin.inventory.repository.VoucherSaleRepository;
import com.fsp.plugin.inventory.repository.InventoryEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Voucher release transitions for the two-phase dispense ({@link VoucherService}) — a separate
 *  bean so the proxied @Transactional semantics survive VoucherService's orchestration. */
@Service
public class VoucherTx {

    private final VoucherRepository vouchers;
    private final VoucherSaleRepository sales;
    private final VoucherBatchRepository batches;
    private final InventoryEventRepository events;

    public VoucherTx(VoucherRepository vouchers, VoucherSaleRepository sales,
                     VoucherBatchRepository batches, InventoryEventRepository events) {
        this.vouchers = vouchers;
        this.sales = sales;
        this.batches = batches;
        this.events = events;
    }

    @Transactional
    public UUID commitSale(UUID tenantId, UUID personId, String saleRef, String channel, UUID productId,
                           UUID batchId, UUID voucherId, long amount, UUID entryId) {
        Voucher voucher = vouchers.findById(voucherId).orElseThrow();
        if (!Voucher.STATUS_RESERVED.equals(voucher.status)) {
            throw ApiProblemException.conflict("VOUCHER_NOT_RESERVED", "Reservation was lost; retry the dispense");
        }
        VoucherSale sale = new VoucherSale();
        sale.id = UUID.randomUUID();
        sale.ownerTenantId = tenantId;
        sale.digitalProductId = productId;
        sale.voucherId = voucher.id;
        sale.saleReference = saleRef;
        sale.channel = channel == null || channel.isBlank() ? "POS" : channel.trim();
        sale.amountMinor = amount;
        sale.currency = voucher.currency;
        sale.journalEntryId = entryId;
        sale.dispensedBy = personId;
        sales.save(sale);

        voucher.status = Voucher.STATUS_RELEASED;
        voucher.releasedAt = Instant.now();
        voucher.releaseSaleId = sale.id;
        vouchers.save(voucher);

        if (vouchers.countByBatchIdAndStatus(batchId, Voucher.STATUS_AVAILABLE) == 0
            && vouchers.countByBatchIdAndStatus(batchId, Voucher.STATUS_RESERVED) == 0) {
            VoucherBatch pool = batches.findById(batchId).orElseThrow();
            pool.status = VoucherBatch.STATUS_EXHAUSTED;
            batches.save(pool);
            Event.record(events, tenantId, "VOUCHER_POOL_EXHAUSTED", "VOUCHER_BATCH", pool.id, Map.of());
        }
        return sale.id;
    }

    /** Failed money call: give the reservation back to the pool (short tx, no lock). */
    @Transactional
    public void rollbackClaim(UUID voucherId) {
        Voucher voucher = vouchers.findById(voucherId).orElseThrow();
        if (Voucher.STATUS_RESERVED.equals(voucher.status) && voucher.releaseSaleId == null) {
            voucher.status = Voucher.STATUS_AVAILABLE;
            voucher.reservedAt = null;
            vouchers.save(voucher);
        }
    }
}
