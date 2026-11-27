package com.fsp.core.identity;

import com.fsp.core.approval.ApprovalHandler;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.common.Actor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Applies an approved {@code USER_PASSWORD_RESET}: triggers the IdP to send a reset
 * link to the person's email. Core never sees or touches the new password.
 *
 * <p>Not atomic with resolution — the IdP dispatch is remote, so the approval row is
 * resolved first and the handler runs outside the DB transaction. The reset is
 * idempotent-ish (one live reset per person, 15 min TTL) so a failed IdP call can be
 * retried by re-opening a new reset request.
 */
@Slf4j
@Component
public class UserPasswordResetApprovalHandler implements ApprovalHandler {

    private final ObjectProvider<UserAdminService> users;
    private final IdpInternalClient idp;

    public UserPasswordResetApprovalHandler(ObjectProvider<UserAdminService> users, IdpInternalClient idp) {
        this.users = users;
        this.idp = idp;
    }

    @Override
    public boolean atomicWithResolution() {
        return false; // remote dispatch to IdP — keeps its own boundary
    }

    @Override
    public String operationType() {
        return ApprovalRequest.OpType.USER_PASSWORD_RESET;
    }

    @Override
    public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
        if (!approved) {
            log.info("user_password_reset_rejected approvalId={} target={} checker={}", approval.id,
                approval.targetResourceId, checker.personId());
            return;
        }
        if (approval.targetResourceId == null) {
            throw new IllegalStateException("USER_PASSWORD_RESET approval missing target");
        }
        // IdP dispatches the reset link; core never sees or touches the new password.
        idp.triggerPasswordReset(approval.targetResourceId);
        log.info("user_password_reset_applied personId={} checker={}", approval.targetResourceId, checker.personId());
    }
}
