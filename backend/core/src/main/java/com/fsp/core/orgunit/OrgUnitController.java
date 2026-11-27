package com.fsp.core.orgunit;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * An organization's branches (maintained here, four-eyes — {@link BranchService}) and departments
 * (read side). Reads need {@code role.view} or {@code role.manage} — re-checked in the services
 * because the operation catalog binds one key per route; writes need {@code role.manage} and
 * answer 202 with the approval they opened.
 */
@RestController
public class OrgUnitController {

    private static final String BRANCHES = "/v1/tenants/{tenantId}/branches";

    private final OrgUnitService service;
    private final BranchService branches;

    public OrgUnitController(OrgUnitService service, BranchService branches) {
        this.service = service;
        this.branches = branches;
    }

    @FspOperation(id = "org.branch.list", component = "orgunit", risk = FspOperation.OperationRisk.READ,
        title = "List an organization's branches")
    @GetMapping(BRANCHES)
    public BranchDtos.BranchPage branches(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                          @RequestParam(value = "page", defaultValue = "0") int page,
                                          @RequestParam(value = "size", defaultValue = "20") int size,
                                          @RequestParam(value = "sort", required = false) String sort,
                                          @RequestParam(value = "q", required = false) String q,
                                          @RequestParam(value = "status", required = false) String status) {
        return branches.list(tenantId, page, size, sort, q, status, actor(request));
    }

    // role.view at the gateway (every role.manage holder also holds it — V19 grants them together);
    // BranchService re-checks role.view OR role.manage
    @FspOperation(id = "org.branch.get", component = "orgunit", permission = "role.view",
        risk = FspOperation.OperationRisk.READ, title = "Read one branch")
    @GetMapping(BRANCHES + "/{branchId}")
    public BranchDtos.BranchView branch(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                        @PathVariable("branchId") UUID branchId) {
        return branches.get(tenantId, branchId, actor(request));
    }

    @FspOperation(id = "org.branch.create", component = "orgunit", permission = "role.manage",
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Request a new branch (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @PostMapping(BRANCHES)
    public ResponseEntity<BranchDtos.BranchChange> createBranch(HttpServletRequest request,
                                                                @PathVariable("tenantId") UUID tenantId,
                                                                @RequestBody BranchDtos.CreateBranch body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(branches.requestCreate(tenantId, body, actor(request)));
    }

    @FspOperation(id = "org.branch.update", component = "orgunit", permission = "role.manage",
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Request a branch rename (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @PatchMapping(BRANCHES + "/{branchId}")
    public ResponseEntity<BranchDtos.BranchChange> updateBranch(HttpServletRequest request,
                                                                @PathVariable("tenantId") UUID tenantId,
                                                                @PathVariable("branchId") UUID branchId,
                                                                @RequestBody BranchDtos.UpdateBranch body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(branches.requestUpdate(tenantId, branchId, body, actor(request)));
    }

    @FspOperation(id = "org.branch.delete", component = "orgunit", permission = "role.manage",
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Request a branch deletion (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @DeleteMapping(BRANCHES + "/{branchId}")
    public ResponseEntity<BranchDtos.BranchChange> deleteBranch(HttpServletRequest request,
                                                                @PathVariable("tenantId") UUID tenantId,
                                                                @PathVariable("branchId") UUID branchId,
                                                                @RequestParam(value = "expectedVersion", required = false) Long expectedVersion) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(branches.requestDelete(tenantId, branchId, expectedVersion, actor(request)));
    }

    @FspOperation(id = "org.department.list", component = "orgunit", risk = FspOperation.OperationRisk.READ,
        title = "List an organization's departments")
    @GetMapping("/v1/tenants/{tenantId}/departments")
    public OrgUnitDtos.OrgUnitPage departments(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                               @RequestParam(value = "page", defaultValue = "0") int page,
                                               @RequestParam(value = "size", defaultValue = "20") int size,
                                               @RequestParam(value = "sort", required = false) String sort,
                                               @RequestParam(value = "q", required = false) String q,
                                               @RequestParam(value = "status", required = false) String status) {
        return service.list(OrgUnitService.Kind.DEPARTMENT, tenantId, page, size, sort, q, status, actor(request));
    }

    private static Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }
}
