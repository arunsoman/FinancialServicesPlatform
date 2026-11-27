package com.fsp.psp.api;

import com.fsp.psp.common.ApiException;
import com.fsp.psp.merchant.Merchant;
import com.fsp.psp.settlement.SettlementService;
import com.fsp.psp.statement.StatementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settlement + statement (recon) surface (plan §11 Stage 5). In Phase-0 these ops run
 * person-plane (the sweeper also runs scheduled); when the staff plane arrives they gain
 * a permission key and the gateway consults core as PDP (PspOperation javadoc contract).
 */
@RestController
public class PspSettlementController {

    private final SettlementService settlements;
    private final StatementService statements;
    private final com.fsp.psp.settlement.MerchantPayableRepository payables;
    private final com.fsp.psp.merchant.MerchantService merchants;

    public PspSettlementController(SettlementService settlements, StatementService statements,
                            com.fsp.psp.settlement.MerchantPayableRepository payables,
                            com.fsp.psp.merchant.MerchantService merchants) {
        this.settlements = settlements;
        this.statements = statements;
        this.payables = payables;
        this.merchants = merchants;
    }

    @PspOperation(id = "psp.settlement.sweep", risk = PspOperation.Risk.WRITE,
        title = "Sweep a closed settlement cycle (closing_debit → merchant payable)")
    @PostMapping(value = "/v1/psp/settlement/sweep", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> sweep(HttpServletRequest http, @RequestBody SweepRequest body) {
        actor(http);
        String connectorCode = body.connectorCode() == null ? "" : body.connectorCode().strip();
        String currency = body.currency() == null ? "" : body.currency().strip().toUpperCase();
        java.time.LocalDate cycle;
        try {
            if (body.cycleDate() == null || body.cycleDate().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_CYCLE_INVALID",
                    "cycleDate is required (yyyy-MM-dd)");
            }
            cycle = java.time.LocalDate.parse(body.cycleDate().strip());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_CYCLE_INVALID",
                "cycleDate must be yyyy-MM-dd");
        }
        var result = settlements.sweep(connectorCode, currency, cycle);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", result.batchId().toString());
        out.put("connectorCode", result.connectorCode());
        out.put("currency", result.currency());
        out.put("cycleDate", result.cycleDate().toString());
        out.put("state", result.state());
        out.put("settledCount", result.settledCount());
        out.put("settledNetMinor", String.valueOf(result.settledNetMinor())); // §2b
        out.put("replay", result.replay());
        return out;
    }

    @PspOperation(id = "psp.statement.ingest", risk = PspOperation.Risk.WRITE,
        title = "Ingest a recon statement (hash-claim-once)")
    @PostMapping(value = "/v1/psp/statement/ingest", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ingestStatement(HttpServletRequest http,
                                               @RequestBody IngestRequest body) {
        actor(http);
        List<StatementService.StatementRow> rows = body.rows() == null ? List.of()
            : body.rows().stream()
                .map(r -> new StatementService.StatementRow(r.railRef(), r.amountMinor()))
                .toList();
        var report = statements.ingest(body.connectorCode(), body.source(), rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", report.id().toString());
        out.put("connectorCode", report.connectorCode());
        out.put("source", report.source());
        out.put("fileHash", report.fileHash());
        out.put("rowsTotal", report.rowsTotal());
        out.put("rowsMatched", report.rowsMatched());
        out.put("rowsMismatched", report.rowsMismatched());
        out.put("mismatches", report.mismatches());
        out.put("replay", report.replay());
        return out;
    }

    @PspOperation(id = "psp.payable.history", title = "List a merchant's payable accruals")
    @GetMapping(value = "/v1/psp/merchants/{merchantId}/payables",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> payables(HttpServletRequest http, @PathVariable UUID merchantId) {
        PspActor actor = actor(http);
        Merchant m = merchants.byId(actor.tenantId(), merchantId); // tenant scoping (404)
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchantId", m.id.toString());
        out.put("payables", payables.forMerchant(m.id, null).stream()
            .map(p -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", p.id.toString());
                row.put("currency", p.currency);
                row.put("periodStart", p.periodStart.toString());
                row.put("periodEnd", p.periodEnd.toString());
                row.put("accrualMinor", money(p.accrualMinor));
                row.put("paidMinor", money(p.paidMinor));
                return row;
            }).toList());
        return out;
    }

    private static String money(java.math.BigDecimal v) {
        return v.longValueExact() + ""; // §2b string on the wire
    }

    static PspActor actor(HttpServletRequest http) {
        return MerchantController.actor(http);
    }

    public record SweepRequest(String connectorCode, String currency, String cycleDate) {}

    public record IngestRequest(String connectorCode, String source,
                                List<Row> rows) {
        public record Row(String railRef, long amountMinor) {}
    }
}
