package com.fsp.core.rbac;

import com.fsp.core.approval.ApprovalHandler;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.common.Actor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Domain effects of resolved ROLE_* approvals → {@link RoleAdminService}. ObjectProvider breaks the
 * ApprovalService → handler → RoleAdminService → ApprovalService bean cycle (same as the user handlers).
 */
public final class RoleApprovalHandlers {

    private RoleApprovalHandlers() {}

    @Component
    static class Create implements ApprovalHandler {
        private final ObjectProvider<RoleAdminService> roles;

        Create(ObjectProvider<RoleAdminService> roles) {
            this.roles = roles;
        }

        @Override
        public boolean atomicWithResolution() {
            return true; // DB-only: grants commit with the approval row or not at all
        }

        @Override
        public String operationType() {
            return ApprovalRequest.OpType.ROLE_CREATE;
        }

        @Override
        public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
            if (approved) roles.getObject().applyCreate(approval, checker);
            else roles.getObject().rejected(approval, checker);
        }
    }

    @Component
    static class Update implements ApprovalHandler {
        private final ObjectProvider<RoleAdminService> roles;

        Update(ObjectProvider<RoleAdminService> roles) {
            this.roles = roles;
        }

        @Override
        public boolean atomicWithResolution() {
            return true; // DB-only: grants commit with the approval row or not at all
        }

        @Override
        public String operationType() {
            return ApprovalRequest.OpType.ROLE_UPDATE;
        }

        @Override
        public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
            if (approved) roles.getObject().applyUpdate(approval, checker);
            else roles.getObject().rejected(approval, checker);
        }
    }

    @Component
    static class Delete implements ApprovalHandler {
        private final ObjectProvider<RoleAdminService> roles;

        Delete(ObjectProvider<RoleAdminService> roles) {
            this.roles = roles;
        }

        @Override
        public boolean atomicWithResolution() {
            return true; // DB-only: grants commit with the approval row or not at all
        }

        @Override
        public String operationType() {
            return ApprovalRequest.OpType.ROLE_DELETE;
        }

        @Override
        public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
            if (approved) roles.getObject().applyDelete(approval, checker);
            else roles.getObject().rejected(approval, checker);
        }
    }
}
