package com.fsp.core.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.HashUtil;
import com.fsp.core.common.KeyLocks;
import com.fsp.core.journal.JournalDtos;
import com.fsp.core.journal.JournalService;
import com.fsp.core.ledger.GlAccount;
import com.fsp.core.ledger.GlAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stage 4 SKU money — the core order brain (plan §7.1). Core orchestrates; EMI decides
 * sufficiency (wallet bounds); TB books the money (linked pending chain). This service
 * owns ONLY order truth + the price fingerprint audit bond:
 *
 *  1. begin(): fingerprint the quoted breakdown EXACTLY like EMI's ChargeService —
 *     basis string "sku|<walletId>|<orderRef>|<merch>|<fee>|<tax>|<currency>" — persist
 *     the order CREATED, then call EMI. A repo price change between quote and charge
 *     surfaces as EMI_PRICE_FINGERPRINT_MISMATCH (409), never a silent repricing (R1).
 *  2. confirm(): EMI committed the charge (fulfilment precondition flipped downstream).
 *  3. cancel(): EMI released the hold (pre-fulfilment only).
 *  4. chargeIntent(): mirror JE on the core book (DR SKU clearing 2211 / CR SKU revenue
 *     4011, resolved by GL code; explicit op, requires je.post.immediate) so the ledger
 *     reflects real SKU values in BOTH books.
 *  5. sync(): EMI is the money truth for the legs — a TB grace lapse (EMI_EXPIRED) or a
 *     void done directly at EMI is repaired into core state here.
 *
 * Transactional integrity (§2.5): order mutation happens in its own short transaction
 * (no remote I/O held); the EMI hop is called between transactions exactly like the
 * inventory dispense TX1/TX2 pattern — a crash mid-flow leaves a recoverable state that
 * sync()/cancel() folds to truth on the next operation.
 */
@Slf4j
@Service
public class SkuOrderService {

    /** Mirror-JE GL codes (core book): SKU clearing (2211), SKU revenue (4011). */
    static final int GL_CODE_SKU_CLEARING = 2211;
    static final int GL_CODE_SKU_REVENUE = 4011;

    private final SkuOrderRepository orders;
    private final GlAccountRepository glAccounts;
    private final EmiMoneyClient emi;
    private final JournalService journal;
    private final KeyLocks locks;
    private final TransactionTemplate tx;

    public SkuOrderService(SkuOrderRepository orders, GlAccountRepository glAccounts,
                           EmiMoneyClient emi, JournalService journal, KeyLocks locks,
                           TransactionTemplate tx) {
        this.orders = orders;
        this.glAccounts = glAccounts;
        this.emi = emi;
        this.journal = journal;
        this.locks = locks;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ begin (quote + charge)

    /**
     * Creates (or idempotently replays) the order and places the EMI charge. Idempotency
     * key = buyer + orderRef; a replay with the SAME fingerprint returns the original
     * order; a DIFFERENT fingerprint is a conflict (somebody re-quoted between calls).
     */
    public SkuOrder begin(UUID tenantId, Actor actor, String orderRef, String skuCode,
                          String description, UUID bookId, UUID walletId, String currency,
                          long merchMinor, long feeMinor, long taxMinor) {
        validRef(skuCode, "skuCode");
        validRef(description == null ? "" : description, "description");
        if (merchMinor < 0 || feeMinor < 0 || taxMinor < 0 || merchMinor + feeMinor + taxMinor <= 0) {
            throw ApiException.badRequest("SKU_QUOTE_INVALID", "quote amounts must sum to a positive total");
        }
        if (!"NAD".equals(currency)) {
            throw ApiException.badRequest("SKU_CURRENCY_UNSUPPORTED", "regulated wallet charges are NAD-only (PSD-3)");
        }

        return locks.withLock("sku-order", actor.personId(), () -> {
            // fingerprint — MIRROR of com.fsp.emi.charge.ChargeService.fingerprint; keep in
            // lockstep forever (the audit bond is only as strong as this byte-identity).
            String fingerprint = fingerprintOf(walletId, orderRef, merchMinor, feeMinor, taxMinor, currency);

            SkuOrder replayed = orders.findByBuyerPersonIdAndOrderRef(actor.personId(), orderRef).orElse(null);
            if (replayed != null) {
                if (!replayed.tenantId.equals(tenantId)) {
                    throw ApiException.notFound("SKU_ORDER_NOT_FOUND", "order not found"); // subtree isolation
                }
                if (!replayed.priceFingerprint.equals(fingerprint)) {
                    throw ApiException.conflict("SKU_PRICE_FINGERPRINT_MISMATCH",
                        "order_ref is already quoted at a different price; use a new orderRef");
                }
                return replayed; // already held/confirmed — EMI idempotency anchors the legs
            }

            SkuOrder order = new SkuOrder();
            order.id = UUID.randomUUID();
            order.tenantId = tenantId;
            order.buyerPersonId = actor.personId();
            order.orderRef = orderRef.strip();
            order.skuCode = skuCode;
            order.description = description;
            order.bookId = bookId;
            order.walletId = walletId;
            order.currency = currency;
            order.merchMinor = BigDecimal.valueOf(merchMinor);
            order.feeMinor = BigDecimal.valueOf(feeMinor);
            order.taxMinor = BigDecimal.valueOf(taxMinor);
            order.totalMinor = BigDecimal.valueOf(merchMinor + feeMinor + taxMinor);
            order.priceFingerprint = fingerprint;
            order.state = SkuOrder.State.CREATED;
            order.createdAt = Instant.now();
            order.updatedAt = order.createdAt;
            tx.execute(s -> orders.save(order)); // TX1: truth first, then the remote hop

            // Remote hop OUTSIDE the DB transaction (no network I/O under a tx, §2.8-family).
            UUID chargeId = emi.charge(walletId, actor.personId(), tenantId, actor.username(),
                order.orderRef, order.merchMinor, order.feeMinor, order.taxMinor,
                currency, fingerprint);
            order.chargeId = chargeId;
            order.state = SkuOrder.State.CHARGE_HELD;
            order.updatedAt = Instant.now();
            return tx.execute(s -> orders.save(order)); // TX2
        });
    }

    /** Public helper so EMI and core hash byte-identically — keep the basis string in ONE place per side. */
    public static String fingerprintOf(UUID walletId, String orderRef, long merchMinor,
                                       long feeMinor, long taxMinor, String currency) {
        // byte-identical to EMI ChargeService.fingerprint(): sku|wallet|ref|merch|fee|tax|ccy
        String basis = "sku|%s|%s|%d|%d|%d|%s".formatted(walletId, orderRef,
            merchMinor, feeMinor, taxMinor, currency);
        return HashUtil.sha256Hex(basis);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Fulfilment confirmed: commit the EMI charge chain; all legs land atomically at TB. */
    public SkuOrder confirm(UUID orderId, Actor actor) {
        return locks.withLock("sku-order", orderId, () -> {
            SkuOrder order = owned(orderId, actor);
            if (SkuOrder.State.CHARGE_CONFIRMED.equals(order.state)) return order; // idempotent replay
            if (!SkuOrder.State.CHARGE_HELD.equals(order.state)) {
                throw ApiException.conflict("SKU_ORDER_STATE",
                    "order is " + order.state + "; confirm applies only to CHARGE_HELD");
            }
            emi.confirm(order.chargeId, actor.personId(), order.tenantId, actor.username());
            order.state = SkuOrder.State.CHARGE_CONFIRMED;
            order.updatedAt = Instant.now();
            return tx.execute(s -> orders.save(order));
        });
    }

    /** Pre-fulfilment cancel: release the EMI hold; post-fulfilment uses the dispute flow. */
    public SkuOrder cancel(UUID orderId, Actor actor) {
        return locks.withLock("sku-order", orderId, () -> {
            SkuOrder order = owned(orderId, actor);
            if (SkuOrder.State.CHARGE_VOIDED.equals(order.state)
                    || SkuOrder.State.CHARGE_EXPIRED.equals(order.state)) {
                return order;
            }
            if (SkuOrder.State.CHARGE_CONFIRMED.equals(order.state)) {
                throw ApiException.conflict("SKU_ORDER_COMMITTED",
                    "confirmed orders resolve through the dispute flow, not inline cancel");
            }
            if (order.chargeId != null) {
                emi.voidCharge(order.chargeId, actor.personId(), order.tenantId, actor.username());
            }
            order.state = SkuOrder.State.CHARGE_VOIDED;
            order.updatedAt = Instant.now();
            return tx.execute(s -> orders.save(order));
        });
    }

    /** Folds EMI-side truth (expiry, remote void) back into the core order state. */
    public SkuOrder sync(UUID orderId, Actor actor) {
        return locks.withLock("sku-order", orderId, () -> {
            SkuOrder order = owned(orderId, actor);
            if (order.chargeId == null) return order;
            var charge = emi.getCharge(order.chargeId, actor.personId(), order.tenantId, actor.username());
            String emiState = chargetate(charge);
            String core = switch (emiState) {
                case "HOLD" -> SkuOrder.State.CHARGE_HELD;
                case "CONFIRMED" -> SkuOrder.State.CHARGE_CONFIRMED;
                case "VOIDED" -> SkuOrder.State.CHARGE_VOIDED;
                case "EXPIRED" -> SkuOrder.State.CHARGE_EXPIRED;
                default -> throw ApiException.conflict("SKU_SYNC_UNKNOWN",
                    "EMI charge state " + emiState + " has no core mapping");
            };
            if (!order.state.equals(core)) {
                log.info("sku_order_sync order={} {} -> {}", order.id, order.state, core);
                order.state = core;
                order.updatedAt = Instant.now();
                SkuOrder saved = order;
                order = tx.execute(s -> orders.save(saved));
            }
            return order;
        });
    }

    // ------------------------------------------------------------------ mirror JE (fingerprint audit)

    /**
     * Posts the mirror JE on the core book: DR SKU clearing (2211) / CR SKU revenue
     * (4011), total minor units as a string (§2b). The entry stores the price
     * fingerprint in its description so any auditor can re-hash the charged breakdown
     * against the order row. Requires je.post.immediate (single-actor intent plane).
     */
    public SkuOrder chargeIntent(UUID orderId, Actor actor) {
        return locks.withLock("sku-order", orderId, () -> {
            SkuOrder order = owned(orderId, actor);
            if (order.journalEntryId != null) return order; // idempotent
            if (!SkuOrder.State.CHARGE_CONFIRMED.equals(order.state)
                    && !SkuOrder.State.CHARGE_HELD.equals(order.state)) {
                throw ApiException.conflict("SKU_ORDER_STATE",
                    "mirror JE applies to held or confirmed charges, not " + order.state);
            }
            if (order.bookId == null) {
                throw ApiException.badRequest("SKU_BOOK_REQUIRED",
                    "order was created without a book; mirror JE requires bookId");
            }
            GlAccount clearing = glAccounts.findByBookIdAndCode(order.bookId, GL_CODE_SKU_CLEARING)
                .orElseThrow(() -> ApiException.conflict("SKU_COA_MISSING",
                    "GL " + GL_CODE_SKU_CLEARING + " (SKU clearing) missing from the book; ensure CoA first"));
            GlAccount revenue = glAccounts.findByBookIdAndCode(order.bookId, GL_CODE_SKU_REVENUE)
                .orElseThrow(() -> ApiException.conflict("SKU_COA_MISSING",
                    "GL " + GL_CODE_SKU_REVENUE + " (SKU revenue) missing from the book; ensure CoA first"));
            var posting = java.time.LocalDate.now();
            var entry = journal.postIntent(order.tenantId, new JournalDtos.CreateEntryRequest(
                order.bookId, posting, posting,
                ("SKU charge mirror order-ref=%s fingerprint=%s").formatted(
                    order.orderRef, order.priceFingerprint),
                order.orderRef,
                List.of(
                    new JournalDtos.LineRequest(clearing.id, "DEBIT", order.total() + "", "SKU charge DR"),
                    new JournalDtos.LineRequest(revenue.id, "CREDIT", order.total() + "", "SKU revenue CR"))
            ), actor);
            order.journalEntryId = entry.entryId();
            order.updatedAt = Instant.now();
            log.info("sku_order_mirror_posted order={} entry={} fingerprintBound=true", order.id, entry.entryId());
            return tx.execute(s -> orders.save(order));
        });
    }

    // ------------------------------------------------------------------ guards

    private SkuOrder owned(UUID orderId, Actor actor) {
        SkuOrder order = orders.findById(orderId)
            .orElseThrow(() -> ApiException.notFound("SKU_ORDER_NOT_FOUND", "order not found"));
        // tenant subtree isolation + owner isolation: foreign order answers 404, never 403
        if (!order.tenantId.equals(actor.activeTenantId())
                || !order.buyerPersonId.equals(actor.personId())) {
            throw ApiException.notFound("SKU_ORDER_NOT_FOUND", "order not found");
        }
        return order;
    }

    private static String chargetate(JsonNode charge) {
        return charge.path("state").asText("UNKNOWN");
    }

    private static void validRef(String v, String field) {
        if (v == null || v.isBlank() || v.length() > (field.equals("skuCode") ? 64 : 256)) {
            throw ApiException.badRequest("SKU_INPUT_INVALID", field + " must be present and bounded");
        }
    }
}
