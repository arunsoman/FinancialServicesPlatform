package com.fsp.core.approval;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import com.fsp.core.rbac.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Four-eyes queue + generic openings (PRD §6). Domain resolution dispatches via handlers. */
@Slf4j
@RestController
public class ApprovalController {

    private final ApprovalService approvalService;
    private final PermissionService permissions;

    public ApprovalController(ApprovalService approvalService, PermissionService permissions) {
        this.approvalService = approvalService;
        this.permissions = permissions;
    }

    /** GET /v1/approvals?tenantId=...&status=PENDING_CHECKER&operationType=UI_LAYOUT_PUBLISH&size? — bounded (guide §4A). */
    @FspOperation(id = "approval.queue.read", component = "approval", title = "View approval queue")
    @GetMapping("/v1/approvals")
    public List<ApprovalDtos.QueueView> queue(jakarta.servlet.http.HttpServletRequest request,
                                              @RequestParam("tenantId") UUID tenantId,
                                              @RequestParam(value = "status", required = false) String status,
                                              @RequestParam(value = "operationType", required = false) String operationType,
                                              @RequestParam(value = "size", required = false, defaultValue = "50") Integer size) {
        if (size < 1 || size > 100) throw ApiException.badRequest("BOUNDS", "size must be 1-100");
        return approvalService.queue(tenantId, status == null ? List.of() : List.of(status), operationType, actor(request))
            .stream().limit(size).toList();
    }

    /** GET /v1/approvals/{id} — one request, with this caller's canDecide/blockedReason. */
    @FspOperation(id = "approval.read", component = "approval", title = "View one approval request")
    @GetMapping("/v1/approvals/{id}")
    public ApprovalDtos.QueueView one(jakarta.servlet.http.HttpServletRequest request, @PathVariable UUID id) {
        return approvalService.viewById(id, actor(request));
    }

    /** POST /v1/approvals — open a GENERIC evidence request (domain flows open their own typed rows). */
    @FspOperation(id = "approval.open", component = "approval",
        risk = FspOperation.OperationRisk.WRITE, title = "Open approval request")
    @PostMapping("/v1/approvals")
    public ApprovalDtos.QueueView open(jakarta.servlet.http.HttpServletRequest request,
                                       @RequestBody ApprovalDtos.OpenRequest body) {
        Actor actor = actor(request);
        permissions.requireAny(actor, List.of("kyc.create", "je.create"), actor.activeTenantId());
        if (body.targetResourceId() == null) throw ApiException.badRequest("APPROVAL_FIELDS", "targetResourceId required");
        var row = approvalService.open(actor.activeTenantId(), body.operationType(), body.targetResourceId(),
            actor, "generic-" + body.targetResourceId(), null);
        return approvalService.view(row, actor);
    }

    @FspOperation(id = "approval.resolve.approve", component = "approval",
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Approve request (four-eyes)",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED})
    @PostMapping("/v1/approvals/{id}/approve")
    public void approve(jakarta.servlet.http.HttpServletRequest request, @PathVariable UUID id,
                        @RequestBody(required = false) ApprovalDtos.DecisionBody body) {
        Actor actor = actor(request);
        log.info("api_approval_approve approvalId={} checker={}", id, actor.personId());
        approvalService.resolve(id, true, actor, body == null ? null : body.reason());
    }

    @FspOperation(id = "approval.resolve.reject", component = "approval",
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Reject request (four-eyes)",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED})
    @PostMapping("/v1/approvals/{id}/reject")
    public void reject(jakarta.servlet.http.HttpServletRequest request, @PathVariable UUID id,
                       @RequestBody ApprovalDtos.DecisionBody body) {
        Actor actor = actor(request);
        if (body == null || body.reason() == null || body.reason().isBlank()) {
            throw ApiException.badRequest("REASON_REQUIRED", "A rejection reason is required");
        }
        log.info("api_approval_reject approvalId={} checker={}", id, actor.personId());
        approvalService.resolve(id, false, actor, body.reason());
    }

    private static Actor actor(jakarta.servlet.http.HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }
}
