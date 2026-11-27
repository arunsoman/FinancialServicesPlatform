package com.fsp.core.approval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApprovalDtos {

    private ApprovalDtos() {}

    public record OpenRequest(String operationType, UUID targetResourceId) {}

    public record DecisionBody(String reason) {}

    /**
     * One queue row as the CALLER sees it (ui-std §5.2 checker surface): who made it, what it
     * proposes ({@code payload}, only for core-owned USER_* / ROLE_* rows — plugin rows carry a
     * hash), and whether THIS caller may decide it. {@code blockedReason} explains a refusal so
     * the UI renders an explicit blocked state instead of hiding the control:
     * OWN_REQUEST | NOT_CHECKER | NO_PERMISSION | PLUGIN_OWNED | DECIDED.
     */
    public record QueueView(UUID id, UUID tenantId, String operationType, UUID targetResourceId,
                            String status, UUID makerId, String makerName, UUID checkerId, String checkerName,
                            String reason, Instant requestedAt, Instant decidedAt, String payload,
                            boolean canDecide, String blockedReason, String checkerPermission) {}
}
