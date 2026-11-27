package com.fsp.core.rbac;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
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

import java.util.List;
import java.util.UUID;

/**
 * Organization roles (GAP-1). Reads need {@code role.view} or {@code role.manage}; every write is
 * STAGED for four-eyes and answers {@code 202 Accepted} with the pending approval reference.
 */
@Slf4j
@RestController
public class RoleAdminController {

    private static final String BASE = "/v1/tenants/{tenantId}/roles";

    private final RoleAdminService service;

    public RoleAdminController(RoleAdminService service) {
        this.service = service;
    }

    @FspOperation(id = "role.list", component = "rbac", permission = "role.view",
        risk = FspOperation.OperationRisk.READ, title = "List an organization's roles")
    @GetMapping(BASE)
    public List<RoleDtos.RoleView> list(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        return service.list(tenantId, actor(request));
    }

    @FspOperation(id = "role.get", component = "rbac", permission = "role.view",
        risk = FspOperation.OperationRisk.READ, title = "Read one role")
    @GetMapping(BASE + "/{roleId}")
    public RoleDtos.RoleView get(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                 @PathVariable("roleId") UUID roleId) {
        return service.get(tenantId, roleId, actor(request));
    }

    @FspOperation(id = "role.create", component = "rbac", permission = "role.manage",
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Request a new role (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @PostMapping(BASE)
    public ResponseEntity<RoleDtos.RoleChange> create(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                                      @RequestBody RoleDtos.CreateRole body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.requestCreate(tenantId, body, actor(request)));
    }

    @FspOperation(id = "role.update", component = "rbac", permission = "role.manage",
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Request a role permission change (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @PatchMapping(BASE + "/{roleId}")
    public ResponseEntity<RoleDtos.RoleChange> update(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                                      @PathVariable("roleId") UUID roleId,
                                                      @RequestBody RoleDtos.UpdateRole body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.requestUpdate(tenantId, roleId, body, actor(request)));
    }

    @FspOperation(id = "role.delete", component = "rbac", permission = "role.manage",
        risk = FspOperation.OperationRisk.FOUR_EYES, title = "Request a role deletion (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @DeleteMapping(BASE + "/{roleId}")
    public ResponseEntity<RoleDtos.RoleChange> delete(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                                      @PathVariable("roleId") UUID roleId,
                                                      @RequestParam(value = "expectedVersion", required = false) Long expectedVersion) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.requestDelete(tenantId, roleId, expectedVersion, actor(request)));
    }

    private static Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }
}
