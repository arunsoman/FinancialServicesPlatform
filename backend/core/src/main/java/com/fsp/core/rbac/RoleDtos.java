package com.fsp.core.rbac;

import java.util.List;
import java.util.UUID;

/** Wire records for organization role governance (RoleAdminController). */
public final class RoleDtos {

    private RoleDtos() {}

    /**
     * A role as an organization sees it. {@code status} is {@code ACTIVE}, or
     * {@code PENDING_CREATE} for a creation still awaiting its checker (no row exists yet).
     * {@code pendingApprovalId}/{@code pendingOperation} name the open four-eyes request, if any.
     */
    public record RoleView(UUID id, UUID tenantId, String key, String name, String scope, String status,
                           List<String> permissionKeys, long version, int assignedCount, boolean protectedRole,
                           UUID pendingApprovalId, String pendingOperation,
                           String description, UUID branchId, String branchName, UUID departmentId, String departmentName,
                           String portal, List<String> menuCells, boolean fullMenu) {}

    /**
     * {@code menuCells}: Permissions Matrix cells ({@code "<menu>/<submenu>:<ACTION>"}) on the
     * published menu of the portal the organization's people sign in to.
     */
    public record CreateRole(String key, String name, String description, UUID branchId, UUID departmentId,
                             List<String> permissionKeys, List<String> menuCells) {}

    /** Absent (null) fields stay as they are. */
    public record UpdateRole(String name, String description, UUID branchId, UUID departmentId,
                             List<String> permissionKeys, List<String> menuCells, Long expectedVersion) {}

    /** Answer of every write: the change is STAGED, not applied (HTTP 202). */
    public record RoleChange(UUID pendingApprovalId, String operationType, UUID roleId, String status) {}

    /** Approval payload — the maker's proposal, re-validated against the checker on apply. */
    public record RoleProposal(String key, String name, List<String> permissionKeys, Long baseVersion,
                               String description, UUID branchId, UUID departmentId,
                               String portal, List<String> menuCells) {

        /** Proposals raised before menu cells existed (still pending in a dev DB) read with nulls. */
        public RoleProposal(String key, String name, List<String> permissionKeys, Long baseVersion) {
            this(key, name, permissionKeys, baseVersion, null, null, null, null, null);
        }
    }
}
