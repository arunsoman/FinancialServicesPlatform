package com.fsp.core.identity;

import com.fsp.core.approval.ApprovalHandler;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.common.Actor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Applies an approved {@code USER_CREATE} decision: activates the person and grants roles,
 * then — only AFTER the approval commits (no remote I/O inside the DB transaction, AGENTS
 * §2.8) — asks the IdP to send the activation to the user's primary contact. The user sets
 * their own password from it; until then the IdP refuses sign-in (ACTIVATION_PENDING).
 * ObjectProvider breaks the ApprovalService → handler → UserAdminService bean cycle.
 */
@Slf4j
@Component
public class UserCreateApprovalHandler implements ApprovalHandler {

    private final ObjectProvider<UserAdminService> users;
    private final ObjectProvider<PersonRepository> persons;
    private final ObjectProvider<IdpInternalClient> idp;

    public UserCreateApprovalHandler(ObjectProvider<UserAdminService> users, ObjectProvider<PersonRepository> persons,
                                     ObjectProvider<IdpInternalClient> idp) {
        this.users = users;
        this.persons = persons;
        this.idp = idp;
    }

    @Override
    public boolean atomicWithResolution() {
        return true; // DB-only: the person change commits with the approval row or not at all
    }

    @Override
    public String operationType() {
        return ApprovalRequest.OpType.USER_CREATE;
    }

    @Override
    public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
        if (!approved) {
            log.info("user_create_rejected approvalId={} target={} checker={}", approval.id,
                approval.targetResourceId, checker.personId());
            return;
        }
        users.getObject().applyCreate(approval.targetResourceId, approval.payload, checker.personId(), reason);
        UUID personId = approval.targetResourceId;
        boolean invited = persons.getObject().findById(personId)
            .map(p -> p.primaryContact != null && p.contactVerifiedAt == null).orElse(false);
        if (!invited) return; // legacy bootstrap-password user: nothing to send
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendActivation(personId);
                }
            });
        } else {
            sendActivation(personId);
        }
    }

    private void sendActivation(UUID personId) {
        try {
            idp.getObject().triggerActivation(personId);
            log.info("user_activation_sent personId={}", personId);
        } catch (RuntimeException e) {
            // the approval stands; the admin can re-send from the user list
            log.warn("user_activation_send_failed personId={} reason={}", personId, e.getClass().getSimpleName());
        }
    }
}
