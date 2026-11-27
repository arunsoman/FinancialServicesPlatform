package com.fsp.core.approval;

import com.fsp.core.common.Actor;

/**
 * Domain effect of a resolved approval. Handlers are looked up by
 * {@link ApprovalRequest.OpType}; a type with no handler is evidence-only.
 */
public interface ApprovalHandler {

    /** Operation type handled (from OpType). */
    String operationType();

    /** Apply the resolved decision to the owning domain. Must be transactional-safe. */
    void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason);

    /**
     * {@code true} = the domain effect is DB-only and runs in ONE transaction with the approval
     * row, so a refused effect (stale role, wrong user state) rolls the resolution back.
     * Default {@code false}: handlers that dispatch to a remote system keep their own transaction
     * boundaries — e.g. JE_POSTING must COMMIT its canonical plan before the TigerBeetle call
     * (AGENTS §3.4), which an enclosing transaction would defeat.
     */
    default boolean atomicWithResolution() {
        return false;
    }
}
