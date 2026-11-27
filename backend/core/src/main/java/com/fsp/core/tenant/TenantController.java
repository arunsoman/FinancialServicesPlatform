package com.fsp.core.tenant;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @FspOperation(id = "tenant.create.psp", component = "tenant", permission = "org.create",
        risk = FspOperation.OperationRisk.WRITE, title = "Create PSP organization")
    @PostMapping("/v1/tenants")
    public TenantDtos.PspCreated createPsp(jakarta.servlet.http.HttpServletRequest request,
                                           @RequestBody TenantDtos.CreatePspRequest body) {
        Actor actor = actor(request);
        TenantDtos.PspCreated created = tenantService.createPsp(actor, body);
        log.info("api_psp_created actor={} tenantId={}", actor.personId(), created.tenantId());
        return created;
    }

    // DELEGATED: a plugin acting for a person (KYC "My organization") reads through the SAME
    // scoped visibility check (TenantService.detail) as the person would — no wider reach.
    @FspOperation(id = "tenant.detail.read", component = "tenant", title = "View tenant detail",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED})
    @GetMapping("/v1/tenants/{tenantId}")
    public TenantDtos.TenantView detail(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        return tenantService.detail(tenantId, actor(request));
    }

    @FspOperation(id = "tenant.children.read", component = "tenant", title = "List tenant children")
    @GetMapping("/v1/tenants/{tenantId}/children")
    public List<TenantDtos.TenantView> children(jakarta.servlet.http.HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        return tenantService.children(tenantId, actor(request));
    }

    @FspOperation(id = "tenant.children.page", component = "tenant", title = "Page tenant children")
    @GetMapping("/v1/tenants/{tenantId}/children/page")
    public TenantDtos.ChildPage childrenPage(jakarta.servlet.http.HttpServletRequest request,
                                             @PathVariable("tenantId") UUID tenantId,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) Integer page,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) Integer size,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) String sort) {
        return tenantService.childrenPage(tenantId, actor(request), page, size, q, sort);
    }

    private static Actor actor(jakarta.servlet.http.HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) {
            throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        }
        return actor;
    }
}
