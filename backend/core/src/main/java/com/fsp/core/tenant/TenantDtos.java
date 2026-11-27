package com.fsp.core.tenant;

import java.time.Instant;
import java.util.UUID;

/** Wire DTOs for tenant management (AGENTS.md §2b: final container + records; never sealed). */
public final class TenantDtos {

    private TenantDtos() {}

    /** Platform admin creates a PSP with its maker + checker personnel (four-eyes ready). */
    public record CreatePspRequest(String legalName, String adminUsername, String adminFullName,
                                   String checkerUsername, String checkerFullName) {}

    public record TenantView(UUID id, UUID parentTenantId, String hierarchyPath, int depth,
                             String type, String subType, String status, String verificationStatus,
                             String legalName, Instant createdAt,
                             /* the only sub-type this org may onboard (TenantLadder); null = none */
                             String childSubType) {}

    /** One server page of an org's children (onboard.list). */
    public record ChildPage(java.util.List<TenantView> items, int page, int size, boolean hasNext,
                            String sort, String q) {}

    public record PspCreated(UUID tenantId, String adminUsername, String checkerUsername,
                             String note) {}
}
