package com.fsp.core.identity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire records for organization user administration (AGENTS §2b: records in a final container). */
public final class UserDtos {

    private UserDtos() {}

    /** Role reference as rendered on a user row. */
    public record RoleRef(UUID id, String key, String name) {}

    /** One person of an organization. Never carries credentials. */
    /**
     * primaryContact: where the IdP sends the activation (EMAIL | PHONE); null for legacy
     * users born with the bootstrap password. activated: whether they set their own password
     * from it (null when there is no activation — legacy users).
     */
    public record UserView(UUID id, UUID tenantId, String username, String fullName, String email,
                           String phone, String status, boolean isApplicant, boolean checker, List<RoleRef> roles,
                           long version, Instant createdAt, Instant updatedAt, Instant archivedAt,
                           String primaryContact, Boolean activated) {}

    /**
     * One page of an organization's users (AGENTS §2f envelope). {@code counts} is per status over
     * the WHOLE organization (PENDING / ACTIVE / DISABLED / ARCHIVED, plus CURRENT = not archived),
     * independent of the page, search and status filter — it drives the status filter chips.
     */
    public record UserPage(List<UserView> items, int page, int size, long totalElements, int totalPages,
                           boolean hasNext, String sort, Map<String, Long> counts, OrgRef org) {}

    /** The organization a users page lists — named here so the page needs no tenant-detail read. */
    public record OrgRef(UUID id, String legalName, String type, String subType, int depth) {}


    /** A role the organization defines, plus whether THIS actor may grant/revoke it. */
    public record RoleOption(UUID id, String key, String name, String scope, int permissionCount,
                             boolean assignable) {}

    /** checker: may resolve four-eyes approvals (docs/checker-flag.md); null = false. */
    /** No password: the IdP sends an activation to the primary contact (EMAIL | PHONE) once
     *  the USER_CREATE is approved, and the user sets their own password from it. */
    public record CreateUser(String username, String fullName, String email, String phone,
                             List<UUID> roleIds, Boolean checker, String primaryContact) {}

    public record ActivationSent(UUID personId, String channel) {}

    /** Partial update; null fields are left unchanged. roleIds == null keeps current roles. */
    public record UpdateUser(String fullName, String email, String phone, List<UUID> roleIds,
                             Boolean checker, Long expectedVersion) {}

    /** Body of activate / deactivate. */
    public record Transition(Long expectedVersion) {}
}
