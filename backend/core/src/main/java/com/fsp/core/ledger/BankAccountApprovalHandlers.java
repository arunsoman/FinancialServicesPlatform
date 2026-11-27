package com.fsp.core.ledger;

import com.fsp.core.approval.ApprovalHandler;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.common.Actor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Domain effect of a resolved BANK_ACCOUNT_CREATE approval → {@link BankAccountService}.
 * DB-only, so the approve/reject commits with the approval row or not at all
 * (same shape as the ROLE_* handlers). ObjectProvider breaks the
 * ApprovalService → handler → BankAccountService → ApprovalService bean cycle.
 */
public final class BankAccountApprovalHandlers {

    private BankAccountApprovalHandlers() {}

    @Component
    static class Create implements ApprovalHandler {
        private final ObjectProvider<BankAccountService> accounts;

        Create(ObjectProvider<BankAccountService> accounts) {
            this.accounts = accounts;
        }

        @Override
        public boolean atomicWithResolution() {
            return true; // DB-only: the account transition commits with the approval row
        }

        @Override
        public String operationType() {
            return ApprovalRequest.OpType.BANK_ACCOUNT_CREATE;
        }

        @Override
        public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
            if (approved) accounts.getObject().activate(approval, checker);
            else accounts.getObject().rejected(approval, checker);
        }
    }
}
