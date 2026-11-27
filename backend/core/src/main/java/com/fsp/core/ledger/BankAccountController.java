package com.fsp.core.ledger;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Settlement bank accounts (F-10). Mutating requests ride the IdempotencyFilter (AGENTS §2e);
 * the four-eyes decision itself lives in the standard approval lane (BANK_ACCOUNT_CREATE).
 */
@RestController
@RequestMapping("/v1/tenants/{tenantId}/bank-accounts")
public class BankAccountController {

    private final BankAccountService service;

    public BankAccountController(BankAccountService service) {
        this.service = service;
    }

    @FspOperation(id = "bank.account.list", permission = "bank.account.view", component = "ledger",
        risk = FspOperation.OperationRisk.READ, title = "List an organization's settlement bank accounts")
    @GetMapping
    public BankAccounts.BankAccountListResponse list(@PathVariable UUID tenantId, HttpServletRequest request) {
        return service.list(tenantId, actor(request));
    }

    /** Maker files a settlement account → PENDING + four-eyes approval (201). */
    @FspOperation(id = "bank.account.create", permission = "bank.account.create", component = "ledger",
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Request a settlement bank account")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BankAccounts.BankAccountView create(@PathVariable UUID tenantId, HttpServletRequest request,
                                               @RequestBody BankAccounts.NewBankAccount body) {
        if (body == null) throw ApiException.badRequest("BODY_INVALID", "Request body required");
        return service.create(tenantId, actor(request), body);
    }

    private static Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }
}
