package com.fsp.core.orgunit;

import com.fsp.core.approval.ApprovalHandler;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.common.Actor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Domain effects of resolved BRANCH_* approvals → {@link BranchService}. All DB-only, so they run in
 * ONE transaction with the approval row: a refused effect (stale version, branch in use, name taken
 * meanwhile) rolls the decision back instead of leaving an APPROVED row whose change never happened.
 * ObjectProvider breaks the ApprovalService → handler → BranchService → ApprovalService bean cycle.
 */
public final class BranchApprovalHandlers {

    private BranchApprovalHandlers() {}

    @Component
    static class Create implements ApprovalHandler {
        private final ObjectProvider<BranchService> branches;

        Create(ObjectProvider<BranchService> branches) {
            this.branches = branches;
        }

        @Override
        public boolean atomicWithResolution() {
            return true;
        }

        @Override
        public String operationType() {
            return ApprovalRequest.OpType.BRANCH_CREATE;
        }

        @Override
        public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
            branches.getObject().decideCreate(approval, approved, checker, reason);
        }
    }

    @Component
    static class Update implements ApprovalHandler {
        private final ObjectProvider<BranchService> branches;

        Update(ObjectProvider<BranchService> branches) {
            this.branches = branches;
        }

        @Override
        public boolean atomicWithResolution() {
            return true;
        }

        @Override
        public String operationType() {
            return ApprovalRequest.OpType.BRANCH_UPDATE;
        }

        @Override
        public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
            if (approved) branches.getObject().applyUpdate(approval, checker);
            else branches.getObject().rejectedChange(approval, checker);
        }
    }

    @Component
    static class Delete implements ApprovalHandler {
        private final ObjectProvider<BranchService> branches;

        Delete(ObjectProvider<BranchService> branches) {
            this.branches = branches;
        }

        @Override
        public boolean atomicWithResolution() {
            return true;
        }

        @Override
        public String operationType() {
            return ApprovalRequest.OpType.BRANCH_DELETE;
        }

        @Override
        public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
            if (approved) branches.getObject().applyDelete(approval, checker);
            else branches.getObject().rejectedChange(approval, checker);
        }
    }
}
