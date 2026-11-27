package com.fsp.core.ledger;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Books + GL surfaces (PRD §6). Provisioning the default CoA per tenant sub-type. */
@Slf4j
@RestController
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/v1/tenants/{tenantId}/books")
    @FspOperation(id = "ledger.book.list", permission = "gl.account.view", component = "ledger",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED, Actor.ActorType.MACHINE},
        risk = FspOperation.OperationRisk.READ, title = "List books")
    public List<Book> books(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        return ledgerService.books(tenantId, actor(request));
    }

    @PostMapping("/v1/tenants/{tenantId}/books/{bookId}/provision-default-coa")
    @FspOperation(id = "ledger.coa.provision", permission = "gl.account.create", component = "ledger",
        risk = FspOperation.OperationRisk.WRITE, title = "Provision default chart of accounts")
    public List<GlAccount> provisionDefaultCoa(jakarta.servlet.http.HttpServletRequest request,
                                               @PathVariable("tenantId") UUID tenantId, @PathVariable("bookId") UUID bookId) {
        log.info("api_coa_provision tenantId={} bookId={}", tenantId, bookId);
        return ledgerService.provisionDefault(tenantId, bookId, actor(request));
    }

    /** Additive, idempotent CoA rows for plugins (plans/inventory-plugin-plan.md D4):
     *  inserts only codes missing from the book; existing accounts at a code are used as-is.
     *  MACHINE is the intended actor (plugin readiness); PERSON stays available for ops. */
    @PostMapping("/v1/tenants/{tenantId}/books/{bookId}/gl-accounts/ensure")
    @FspOperation(id = "ledger.coa.ensure", permission = "gl.account.create", component = "ledger",
        actors = {Actor.ActorType.MACHINE, Actor.ActorType.PERSON},
        risk = FspOperation.OperationRisk.WRITE, title = "Ensure additively-provisioned GL accounts")
    public List<GlAccount> ensureCoa(@org.springframework.web.bind.annotation.RequestBody java.util.List<CoaTemplates.Row> rows,
                                     jakarta.servlet.http.HttpServletRequest request,
                                     @PathVariable("tenantId") UUID tenantId, @PathVariable("bookId") UUID bookId) {
        log.info("api_coa_ensure tenantId={} bookId={} rows={}", tenantId, bookId, rows.size());
        return ledgerService.ensureAdditive(tenantId, bookId, actor(request), rows);
    }

    @GetMapping("/v1/tenants/{tenantId}/books/{bookId}/gl-accounts")
    @FspOperation(id = "ledger.gl_account.list", permission = "gl.account.view", component = "ledger",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED, Actor.ActorType.MACHINE},
        risk = FspOperation.OperationRisk.READ, title = "List GL accounts")
    public List<GlAccount> glAccounts(jakarta.servlet.http.HttpServletRequest request,
                                      @PathVariable("tenantId") UUID tenantId, @PathVariable("bookId") UUID bookId) {
        return ledgerService.glAccounts(tenantId, bookId, actor(request));
    }

    @GetMapping("/v1/tenants/{tenantId}/books/{bookId}/gl-accounts/{glAccountId}")
    @FspOperation(id = "ledger.gl_account.get", permission = "gl.account.view", component = "ledger",
        risk = FspOperation.OperationRisk.READ, title = "GL account detail")
    public GlAccount glAccount(jakarta.servlet.http.HttpServletRequest request,
                               @PathVariable("tenantId") UUID tenantId, @PathVariable("bookId") UUID bookId, @PathVariable("glAccountId") UUID glAccountId) {
        return ledgerService.glAccounts(tenantId, bookId, actor(request)).stream()
            .filter(g -> g.id.equals(glAccountId)).findFirst()
            .orElseThrow(() -> ApiException.notFound("GL_NOT_FOUND", "GL account not found"));
    }

    @GetMapping("/v1/tenants/{tenantId}/books/{bookId}/gl-accounts/{glAccountId}/balance")
    @FspOperation(id = "ledger.gl_account.balance", permission = "gl.account.view", component = "ledger",
        risk = FspOperation.OperationRisk.READ, title = "Live GL balance from TigerBeetle")
    public LedgerService.GlBalanceView balance(jakarta.servlet.http.HttpServletRequest request,
                                               @PathVariable("tenantId") UUID tenantId, @PathVariable("bookId") UUID bookId, @PathVariable("glAccountId") UUID glAccountId) {
        return ledgerService.balance(tenantId, bookId, glAccountId, actor(request));
    }

    @GetMapping("/v1/tenants/{tenantId}/books/{bookId}/periods")
    @FspOperation(id = "ledger.period.list", permission = "gl.account.view", component = "ledger",
        risk = FspOperation.OperationRisk.READ, title = "List accounting periods")
    public List<AccountingPeriod> periods(jakarta.servlet.http.HttpServletRequest request,
                                          @PathVariable("tenantId") UUID tenantId, @PathVariable("bookId") UUID bookId) {
        return ledgerService.periods(tenantId, bookId, actor(request));
    }

    private static Actor actor(jakarta.servlet.http.HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }
}
