package com.fsp.core.rbac;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    List<Role> findByTenantId(UUID tenantId);

    /**
     * Optimistic lock in the database (AGENTS §2d): bumps the version only when it still equals
     * the version the change was based on. Returns 0 when someone else moved the role first.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Role r set r.version = r.version + 1, r.name = :name where r.id = :id and r.version = :expected")
    int bumpVersion(@Param("id") UUID id, @Param("expected") long expected, @Param("name") String name);

    /** {@link #bumpVersion} that also moves the role's details (same pinned-version guard). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Role r set r.version = r.version + 1, r.name = :name, r.description = :description,
               r.branchId = :branchId, r.departmentId = :departmentId
        where r.id = :id and r.version = :expected
        """)
    int bumpVersionWithDetails(@Param("id") UUID id, @Param("expected") long expected, @Param("name") String name,
                               @Param("description") String description, @Param("branchId") UUID branchId,
                               @Param("departmentId") UUID departmentId);
}
