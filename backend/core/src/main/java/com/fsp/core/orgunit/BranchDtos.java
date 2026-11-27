package com.fsp.core.orgunit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire records of the Branch screen (widget org.branches, frontend model.ts — field names are the
 * approved contract). Every write answers 202 with the approval it opened; nothing applies before
 * a different person decides it.
 */
public final class BranchDtos {

    private BranchDtos() {}

    /** The one change waiting for a checker. canDecide / blockedReason are computed for the CALLER. */
    public record PendingChange(UUID approvalId, String operation, String proposedName, UUID requestedById,
                                String requestedByName, Instant requestedAt, boolean canDecide, String blockedReason) {}

    /**
     * One branch. makerId is null (makerName "System") on system-seeded rows; checker* name the
     * person who decided the creation.
     */
    public record BranchView(UUID id, String code, String name, String status, long version,
                             UUID makerId, String makerName, Instant createdAt,
                             UUID checkerId, String checkerName, Instant decidedAt, String decisionReason,
                             Instant updatedAt, PendingChange pending) {}

    /** One page (AGENTS §2f) — a superset of the old OrgUnitPage (id / code / name / status). */
    public record BranchPage(List<BranchView> items, int page, int size, long totalElements, int totalPages,
                             boolean hasNext, String sort) {}

    public record CreateBranch(String name) {}

    public record UpdateBranch(String name, Long expectedVersion) {}

    /** 202 answer of create / update / delete. */
    public record BranchChange(UUID pendingApprovalId, String operationType, UUID branchId, String status) {}

    /** The proposal stored on the approval row (baseVersion first: the q search scans from "name"). */
    public record BranchProposal(Long baseVersion, String name) {}
}
