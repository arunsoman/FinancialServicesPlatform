package com.fsp.core.billing;

import com.fsp.core.approval.ApprovalHandler;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.common.Actor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Domain effect of a resolved INVOICE_VOID approval → {@link InvoiceService}. DB-only, so the
 * decision commits with the approval row or not at all. ObjectProvider breaks the
 * ApprovalService → handler → InvoiceService → ApprovalService bean cycle.
 */
public final class InvoiceApprovalHandlers {

    private InvoiceApprovalHandlers() {}

    @Component
    static class Void implements ApprovalHandler {
        private final ObjectProvider<InvoiceService> invoices;

        Void(ObjectProvider<InvoiceService> invoices) {
            this.invoices = invoices;
        }

        @Override
        public boolean atomicWithResolution() {
            return true;
        }

        @Override
        public String operationType() {
            return ApprovalRequest.OpType.INVOICE_VOID;
        }

        @Override
        public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
            if (approved) invoices.getObject().voidApproved(approval, checker);
            else invoices.getObject().voidRejected(approval, checker);
        }
    }
}
