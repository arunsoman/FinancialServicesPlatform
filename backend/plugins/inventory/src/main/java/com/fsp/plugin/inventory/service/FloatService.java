package com.fsp.plugin.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fsp.plugin.inventory.api.ApiProblemException;
import com.fsp.plugin.inventory.api.InventoryDtos;
import com.fsp.plugin.inventory.domain.FloatDeposit;
import com.fsp.plugin.inventory.repository.FloatDepositRepository;
import com.fsp.plugin.inventory.repository.IdempotencyRecordRepository;
import com.fsp.plugin.inventory.repository.InventoryEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Merchant digital float funding (plans D2/D5, screenplay §F1/F2):
 * four-eyes PLUGIN:FLOAT_FUNDING + the JE (Dr 1000 Cash at Bank / Cr 2050 Merchant Digital
 * Float), posted by CORE only — the plugin stages the JE, the checker's decision approves it.
 */
@Slf4j
@Service
public class FloatService {

    private static final int GL_CASH = 1000;
    private static final int GL_FLOAT = 2050;

    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "floatCode", "floatCode", "status", "status", "createdAt", "createdAt", "decidedAt", "decidedAt");

    private final FloatDepositRepository deposits;
    private final InventoryEventRepository events;
    private final IdempotencyRecordRepository idempotency;
    private final CoreClient core;
    private final GlBookResolver glResolver;

    public FloatService(FloatDepositRepository deposits, InventoryEventRepository events,
                        IdempotencyRecordRepository idempotency, CoreClient core, GlBookResolver glResolver) {
        this.deposits = deposits;
        this.events = events;
        this.idempotency = idempotency;
        this.core = core;
        this.glResolver = glResolver;
    }

    public InventoryDtos.ItemList<InventoryDtos.FloatDepositView> list(UUID tenantId, int page, int size,
                                                                       String q, String status, String sort) {
        Pageable pageable = Paging.page(SORT_COLUMNS, sort, page, size);
        Page<FloatDeposit> p = deposits.search(tenantId, q != null && !q.isBlank(),PluginSupport.searchPattern(q),
            Paging.status(status, FloatDeposit.VALID_STATUSES), pageable);
        return Paging.pageOf(p.map(this::view), size);
    }

    /** maker: stage the JE + open the PLUGIN:FLOAT_FUNDING approval. */
    @Transactional
    public InventoryDtos.FloatDepositView deposit(UUID tenantId, UUID personId,
                                                  InventoryDtos.CreateFloatDepositRequest req,
                                                  String assertion, String idempotencyKey) {
        PluginSupport.requireFresh(idempotency, personId, idempotencyKey);
        String floatCode = PluginSupport.nonBlank(req.floatCode(), "floatCode");
        long amount = PluginSupport.money(req.amountMinor(), "amountMinor");
        if (amount <= 0) throw ApiProblemException.badRequest("FIELD", "amountMinor must be > 0");
        String currency = PluginSupport.currency(req.currency());

        var ledger = glResolver.refs(tenantId);
        if (!ledger.currency().equalsIgnoreCase(currency)) {
            throw ApiProblemException.conflict("CURRENCY_MISMATCH",
                "Float currency " + currency + " != book currency " + ledger.currency());
        }
        glResolver.gls(tenantId, GL_CASH, GL_FLOAT);

        FloatDeposit deposit = new FloatDeposit();
        deposit.id = UUID.randomUUID();
        deposit.ownerTenantId = tenantId;
        deposit.floatCode = floatCode;
        deposit.currency = currency;
        deposit.amountMinor = amount;
        deposit.note = req.note();
        deposit.status = FloatDeposit.STATUS_PENDING;
        deposit.requestedBy = personId;

        String jeKey = "inv-float-je-create-" + tenantId + "-" + deposit.id;
        JsonNode entry = core.createJeDraft(tenantId, Map.of(
            "bookId", ledger.bookId().toString(),
            "entryType", "INVENTORY",
            "description", "Digital float funding " + floatCode,
            "reference", "INV-FLOAT-" + deposit.id,
            "postingDate", LocalDate.now().toString(),
            "lines", List.of(
                Map.of("glAccountId", glResolver.gl(tenantId, GL_CASH).glAccountId().toString(),
                    "direction", "DEBIT", "amountMinor", Long.toString(amount),
                    "memo", "Cash at bank"),
                Map.of("glAccountId", glResolver.gl(tenantId, GL_FLOAT).glAccountId().toString(),
                    "direction", "CREDIT", "amountMinor", Long.toString(amount),
                    "memo", "Merchant digital float " + floatCode))), assertion, jeKey);
        UUID entryId = UUID.fromString(entry.path("entryId").asText());
        deposit.journalEntryId = entryId;

        try {
            core.submitJe(tenantId, entryId, assertion);
        } catch (ApiProblemException e) {
            if (!"JE_STATE".equals(e.getCode())) throw e;
        }

        Map<String, Object> opened = core.openApproval(Map.of(
            "pluginCode", "inventory",
            "action", "FLOAT_FUNDING",
            "subjectType", "FLOAT_DEPOSIT",
            "subjectId", deposit.id,
            "payload", Map.of("floatCode", floatCode, "currency", currency,
                "amountMinor", Long.toString(amount), "journalEntryId", entryId.toString())),
            assertion, "inv-float-approval-" + deposit.id);
        deposit.approvalId = UUID.fromString(String.valueOf(opened.get("approvalId")));

        deposits.save(deposit);
        Event.record(events, tenantId, "FLOAT_DEPOSIT_STAGED", "FLOAT_DEPOSIT", deposit.id,
            Map.of("floatCode", floatCode, "journalEntryId", entryId));
        log.info("float_deposit_staged tenantId={} deposit={} entryId={}", tenantId, deposit.id, entryId);
        PluginSupport.recordIdempotent(idempotency, personId, idempotencyKey, 200);
        return view(deposit);
    }

    /** checker decision: approve → core posts the JE → COMMITTED; reject → REJECTED. */
    @Transactional
    public InventoryDtos.FloatDepositView decide(UUID tenantId, UUID checkerId, UUID depositId,
                                                 InventoryDtos.DecisionBody body, String assertion) {
        if (body.decision() == null || !Set.of("APPROVE", "REJECT").contains(body.decision())) {
            throw ApiProblemException.badRequest("DECISION", "decision must be APPROVE or REJECT");
        }
        FloatDeposit deposit = deposits.findByIdAndOwnerTenantId(depositId, tenantId)
            .orElseThrow(() -> ApiProblemException.notFound("FLOAT_DEPOSIT_NOT_FOUND", "Float deposit not found"));
        if (!FloatDeposit.STATUS_PENDING.equals(deposit.status)) {
            throw ApiProblemException.conflict("NOT_PENDING", "Deposit is not pending approval");
        }
        core.resolveApproval(deposit.approvalId, "APPROVE".equals(body.decision()), body.reason(),
            assertion, "inv-float-decide-" + deposit.id);
        if ("APPROVE".equals(body.decision())) {
            try {
                core.approveJe(tenantId, deposit.journalEntryId, assertion);
            } catch (ApiProblemException e) {
                if ("MAKER_NOT_CHECKER".equals(e.getCode()) || !"JE_STATE".equals(e.getCode())) throw e;
            }
            deposit.status = FloatDeposit.STATUS_COMMITTED;
            Event.record(events, tenantId, "FLOAT_DEPOSIT_COMMITTED", "FLOAT_DEPOSIT", deposit.id,
                Map.of("by", checkerId, "journalEntryId", deposit.journalEntryId));
        } else {
            deposit.status = FloatDeposit.STATUS_REJECTED;
            Event.record(events, tenantId, "FLOAT_DEPOSIT_REJECTED", "FLOAT_DEPOSIT", deposit.id,
                Map.of("by", checkerId, "reason", body.reason() == null ? "" : body.reason()));
        }
        deposit.approvedBy = checkerId;
        deposit.decidedAt = Instant.now();
        deposits.save(deposit);
        log.info("float_deposit_decided tenantId={} deposit={} decision={}", tenantId, deposit.id, body.decision());
        return view(deposit);
    }

    private InventoryDtos.FloatDepositView view(FloatDeposit d) {
        return new InventoryDtos.FloatDepositView(d.id, d.floatCode, d.currency, Long.toString(d.amountMinor),
            d.note, d.status, d.journalEntryId, d.approvalId,
            String.valueOf(d.requestedBy), d.approvedBy == null ? null : String.valueOf(d.approvedBy),
            d.version, d.createdAt, d.decidedAt);
    }
}
