package com.fsp.core.orgunit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    /** Tenant-scoped page; status + q (code or name, case-insensitive; "" = all) pushed to SQL (AGENTS §2f).
     *  q is never null: a null bind has no SQL type for LIKE in PostgreSQL (text ~~ bytea). */
    @Query("""
        select b from Branch b
        where b.tenantId = :tenantId
          and (:status is null or b.status = :status)
          and (lower(b.name) like concat('%', :q, '%') or lower(b.code) like concat('%', :q, '%'))
        """)
    Page<Branch> search(@Param("tenantId") UUID tenantId, @Param("status") String status,
                        @Param("q") String q, Pageable pageable);

    /**
     * The Branch screen's page (BranchService.list): {@code view} is never null — ALL, CURRENT
     * (PENDING + ACTIVE), AWAITING (a change waits for a checker) or one status. {@code q} matches
     * the name, the code, or a rename's proposed name (the BRANCH_UPDATE payload {"baseVersion":…,"name":"…"}).
     */
    @Query("""
        select b from Branch b
        where b.tenantId = :tenantId
          and (:view = 'ALL'
               or (:view = 'CURRENT' and b.status in ('PENDING', 'ACTIVE'))
               or (:view = 'AWAITING' and exists (
                     select a.id from ApprovalRequest a
                     where a.targetResourceId = b.id and a.status = 'PENDING_CHECKER'
                       and a.operationType in ('BRANCH_CREATE', 'BRANCH_UPDATE', 'BRANCH_DELETE')))
               or b.status = :view)
          and (lower(b.name) like concat('%', :q, '%') or lower(b.code) like concat('%', :q, '%')
               or exists (
                     select a.id from ApprovalRequest a
                     where a.targetResourceId = b.id and a.status = 'PENDING_CHECKER'
                       and a.operationType = 'BRANCH_UPDATE'
                       and lower(a.payload) like concat('%"name":"%', :q, '%')))
        """)
    Page<Branch> page(@Param("tenantId") UUID tenantId, @Param("view") String view,
                      @Param("q") String q, Pageable pageable);

    boolean existsByTenantIdAndStatus(UUID tenantId, String status);

    /** Live (PENDING / ACTIVE) branches of the organization with this lower-cased name. */
    @Query("select b.id from Branch b where b.tenantId = :tenantId and lower(b.name) = :lowerName and b.status in ('PENDING', 'ACTIVE')")
    List<UUID> liveIdsByName(@Param("tenantId") UUID tenantId, @Param("lowerName") String lowerName);

    @Query(value = "select nextval('branch_code_seq')", nativeQuery = true)
    long nextCodeNumber();

    /** Roles anchored to the branch — an archived branch must not leave roles pointing at it. */
    @Query(value = "select count(*) from roles where branch_id = :branchId", nativeQuery = true)
    long countRoles(@Param("branchId") UUID branchId);

    // ---- atomic status-precondition transitions (AGENTS §2d): 1 = moved, 0 = stale or wrong state.
    //      The UPDATE row-locks the branch, so concurrent filers / checkers serialize here.

    /** A maker files a rename / deletion: pins the version the proposal was made against. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Branch b set b.version = b.version + 1, b.updatedAt = :now
        where b.id = :id and b.version = :version and b.status = 'ACTIVE'
        """)
    int touchActive(@Param("id") UUID id, @Param("version") long version, @Param("now") Instant now);

    /** The checker decides a creation: PENDING → ACTIVE | REJECTED. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Branch b set b.status = :to, b.checkerId = :checkerId, b.decidedAt = :now, b.decisionReason = :reason,
            b.version = b.version + 1, b.updatedAt = :now
        where b.id = :id and b.version = :version and b.status = 'PENDING'
        """)
    int decideCreation(@Param("id") UUID id, @Param("version") long version, @Param("to") String to,
                       @Param("checkerId") UUID checkerId, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Branch b set b.name = :name, b.version = b.version + 1, b.updatedAt = :now
        where b.id = :id and b.version = :version and b.status = 'ACTIVE'
        """)
    int rename(@Param("id") UUID id, @Param("version") long version, @Param("name") String name, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Branch b set b.status = 'ARCHIVED', b.version = b.version + 1, b.updatedAt = :now
        where b.id = :id and b.version = :version and b.status = 'ACTIVE'
        """)
    int archive(@Param("id") UUID id, @Param("version") long version, @Param("now") Instant now);

    /** A decided change that changed nothing (rejected rename / deletion) still moves the version. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Branch b set b.version = b.version + 1, b.updatedAt = :now where b.id = :id and b.version = :version")
    int bump(@Param("id") UUID id, @Param("version") long version, @Param("now") Instant now);
}
