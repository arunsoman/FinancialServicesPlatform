package com.fsp.plugin.inventory.service;

import com.fsp.plugin.inventory.api.ApiProblemException;
import com.fsp.plugin.inventory.api.InventoryDtos;
import com.fsp.plugin.inventory.domain.DigitalProduct;
import com.fsp.plugin.inventory.repository.DigitalProductRepository;
import com.fsp.plugin.inventory.repository.IdempotencyRecordRepository;
import com.fsp.plugin.inventory.repository.InventoryEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Digital value products (plan D5/D7, screenplay §D1): sale/cost price, fee split
 * (D7: telco %d2 bp, distributor %d2 bp, platform %d2 bp — remainder = merchant margin that
 * balances the dispense JE) and the margin GL (4000 or 4900).
 */
@Slf4j
@Service
public class DigitalProductService {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "code", "code", "name", "name", "status", "status", "createdAt", "createdAt");

    private final DigitalProductRepository products;
    private final InventoryEventRepository events;
    private final IdempotencyRecordRepository idempotency;
    private final GlBookResolver glResolver;

    public DigitalProductService(DigitalProductRepository products, InventoryEventRepository events,
                                 IdempotencyRecordRepository idempotency, GlBookResolver glResolver) {
        this.products = products;
        this.events = events;
        this.idempotency = idempotency;
        this.glResolver = glResolver;
    }

    public InventoryDtos.ItemList<InventoryDtos.DigitalProductView> list(UUID tenantId, int page, int size,
                                                                         String q, String status, String sort) {
        Pageable pageable = Paging.page(SORT_COLUMNS, sort, page, size);
        Page<DigitalProduct> p = products.search(tenantId, q != null && !q.isBlank(),PluginSupport.searchPattern(q),
            Paging.status(status, DigitalProduct.VALID_STATUSES), pageable);
        return Paging.pageOf(p.map(this::view), size);
    }

    public InventoryDtos.DigitalProductView get(UUID tenantId, UUID id) {
        return products.findByIdAndOwnerTenantId(id, tenantId)
            .map(this::view)
            .orElseThrow(() -> ApiProblemException.notFound("DIGITAL_PRODUCT_NOT_FOUND", "Digital product not found"));
    }

    @Transactional
    public InventoryDtos.DigitalProductView create(UUID tenantId, UUID personId,
                                                   InventoryDtos.CreateDigitalProductRequest req,
                                                   String idempotencyKey) {
        PluginSupport.requireFresh(idempotency, personId, idempotencyKey);
        String code = PluginSupport.nonBlank(req.code(), "code");

        long salePrice = PluginSupport.money(req.salePriceMinor(), "salePriceMinor");
        long costPrice = PluginSupport.money(req.costPriceMinor(), "costPriceMinor");
        if (costPrice > salePrice) {
            throw ApiProblemException.badRequest("FIELD", "costPriceMinor cannot exceed salePriceMinor");
        }
        int telcoBp = req.telcoBp() == null ? 9400 : req.telcoBp();
        int distributorBp = req.distributorBp() == null ? 200 : req.distributorBp();
        int platformBp = req.platformBp() == null ? 100 : req.platformBp();
        int marginGl = req.marginGlCode() == null ? 4000 : req.marginGlCode();
        if (telcoBp < 0 || distributorBp < 0 || platformBp < 0
            || telcoBp + distributorBp + platformBp >= 10_000) {
            throw ApiProblemException.badRequest("FIELD",
                "telcoBp + distributorBp + platformBp must be in (0, 10000) — the remainder is the merchant margin");
        }
        String currency = PluginSupport.currency(req.currency());
        // the margin GL must EXIST before a product can route income to it (plan D5)
        glResolver.gls(tenantId, marginGl);

        DigitalProduct product = new DigitalProduct();
        product.id = UUID.randomUUID();
        product.ownerTenantId = tenantId;
        product.code = code;
        product.name = PluginSupport.nonBlank(req.name(), "name");
        product.currency = currency;
        product.salePriceMinor = salePrice;
        product.costPriceMinor = costPrice;
        product.telcoBp = telcoBp;
        product.distributorBp = distributorBp;
        product.platformBp = platformBp;
        product.marginGlCode = marginGl;
        products.save(product);
        Event.record(events, tenantId, "DIGITAL_PRODUCT_CREATED", "DIGITAL_PRODUCT", product.id,
            Map.of("code", code, "marginGl", marginGl));
        log.info("digital_product_created tenantId={} product={} code={}", tenantId, product.id, code);
        PluginSupport.recordIdempotent(idempotency, personId, idempotencyKey, 200);
        return view(product);
    }

    private InventoryDtos.DigitalProductView view(DigitalProduct p) {
        return new InventoryDtos.DigitalProductView(p.id, p.code, p.name, p.kind, p.status,
            Long.toString(p.salePriceMinor), Long.toString(p.costPriceMinor), p.currency,
            p.telcoBp, p.distributorBp, p.platformBp, p.marginGlCode, p.version, p.createdAt);
    }
}
