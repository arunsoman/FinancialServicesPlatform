package com.fsp.core.identity;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Organization user administration — every operation is gated on user.manage at the target org. */
@Slf4j
@RestController
public class UserAdminController {

    private static final String BASE = "/v1/tenants/{tenantId}/users";

    private final UserAdminService service;
    private final IdpInternalClient idp;

    public UserAdminController(UserAdminService service, IdpInternalClient idp) {
        this.service = service;
        this.idp = idp;
    }

    @FspOperation(id = "user.list", component = "identity", permission = UserAdminService.PERMISSION,
        title = "List organization users")
    @GetMapping(BASE)
    public UserDtos.UserPage list(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                  @RequestParam(value = "page", required = false) Integer page,
                                  @RequestParam(value = "size", required = false) Integer size,
                                  @RequestParam(value = "q", required = false) String q,
                                  @RequestParam(value = "status", required = false) String status,
                                  @RequestParam(value = "sort", required = false) String sort) {
        return service.list(tenantId, actor(request), page, size, q, status, sort);
    }

    @FspOperation(id = "user.get", component = "identity", permission = UserAdminService.PERMISSION,
        title = "View organization user")
    @GetMapping(BASE + "/{personId}")
    public UserDtos.UserView get(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                 @PathVariable("personId") UUID personId) {
        return service.get(tenantId, personId, actor(request));
    }

    @FspOperation(id = "user.role.options", component = "identity", permission = UserAdminService.PERMISSION,
        title = "Roles assignable to organization users")
    @GetMapping("/v1/tenants/{tenantId}/user-roles")
    public List<UserDtos.RoleOption> roleOptions(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId) {
        return service.roleOptions(tenantId, actor(request));
    }

    @FspOperation(id = "user.create", component = "identity", permission = UserAdminService.PERMISSION,
        risk = FspOperation.OperationRisk.WRITE, title = "Add organization user")
    @PostMapping(BASE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UserDtos.UserView create(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                    @RequestBody UserDtos.CreateUser body) {
        return service.create(tenantId, body, actor(request));
    }

    @FspOperation(id = "user.update", component = "identity", permission = UserAdminService.PERMISSION,
        risk = FspOperation.OperationRisk.WRITE, title = "Modify organization user")
    @PatchMapping(BASE + "/{personId}")
    public UserDtos.UserView update(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                    @PathVariable("personId") UUID personId,
                                    @RequestBody UserDtos.UpdateUser body) {
        return service.update(tenantId, personId, body, actor(request));
    }

    @FspOperation(id = "user.deactivate", component = "identity", permission = UserAdminService.PERMISSION,
        risk = FspOperation.OperationRisk.WRITE, title = "Deactivate organization user")
    @PostMapping(BASE + "/{personId}/deactivate")
    public UserDtos.UserView deactivate(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                        @PathVariable("personId") UUID personId,
                                        @RequestBody(required = false) UserDtos.Transition body) {
        return service.deactivate(tenantId, personId, body == null ? null : body.expectedVersion(), actor(request));
    }

    @FspOperation(id = "user.activate", component = "identity", permission = UserAdminService.PERMISSION,
        risk = FspOperation.OperationRisk.WRITE, title = "Activate organization user")
    @PostMapping(BASE + "/{personId}/activate")
    public UserDtos.UserView activate(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                      @PathVariable("personId") UUID personId,
                                      @RequestBody(required = false) UserDtos.Transition body) {
        return service.activate(tenantId, personId, body == null ? null : body.expectedVersion(), actor(request));
    }

    /** Soft delete (status ARCHIVED): the record is retained for audit/retention, sign-in is gone for good. */
    @FspOperation(id = "user.remove", component = "identity", permission = UserAdminService.PERMISSION,
        risk = FspOperation.OperationRisk.WRITE, title = "Remove organization user")
    @DeleteMapping(BASE + "/{personId}")
    public UserDtos.UserView remove(HttpServletRequest request, @PathVariable("tenantId") UUID tenantId,
                                    @PathVariable("personId") UUID personId,
                                    @RequestParam(value = "expectedVersion", required = false) Long expectedVersion) {
        return service.remove(tenantId, personId, expectedVersion, actor(request));
    }

    /** Admin-initiated password reset request (four-eyes): opens a USER_PASSWORD_RESET
     *  approval awaiting checker resolution; the IdP then sends the reset link. */
    @FspOperation(id = "user.password.reset.request", component = "identity",
        permission = UserAdminService.PERMISSION,
        risk = FspOperation.OperationRisk.WRITE, title = "Request admin-initiated password reset (four-eyes)")
    @PostMapping(BASE + "/{personId}/password-reset-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UserDtos.UserView passwordResetRequest(HttpServletRequest request,
                                                  @PathVariable("tenantId") UUID tenantId,
                                                  @PathVariable("personId") UUID personId,
                                                  @RequestBody(required = false) UserDtos.Transition body) {
        return service.passwordResetRequest(tenantId, personId,
            body == null ? null : body.expectedVersion(), actor(request));
    }

    /** Re-send the IdP activation (email link / SMS code) to an approved user who hasn't set
     *  their password yet. Supersedes the earlier one. Not four-eyes: it grants nothing — the
     *  user can only set a password for the account the checker already approved. */
    @FspOperation(id = "user.activation.resend", component = "identity",
        permission = UserAdminService.PERMISSION,
        risk = FspOperation.OperationRisk.WRITE, title = "Re-send a user's activation to their primary contact")
    @PostMapping(BASE + "/{personId}/activation")
    public UserDtos.ActivationSent resendActivation(HttpServletRequest request,
                                                    @PathVariable("tenantId") UUID tenantId,
                                                    @PathVariable("personId") UUID personId) {
        Actor a = actor(request);
        Person p = service.requireAwaitingActivation(tenantId, personId, a);
        idp.triggerActivation(p.id); // outside any DB transaction
        service.recordActivationResent(tenantId, p, a);
        log.info("user_activation_resent personId={} tenant={} by={}", p.id, tenantId, a.personId());
        return new UserDtos.ActivationSent(p.id, p.primaryContact);
    }

    private static Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }
}
