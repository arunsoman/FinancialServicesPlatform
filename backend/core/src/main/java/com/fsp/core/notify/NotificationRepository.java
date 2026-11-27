package com.fsp.core.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Notification> {

    @Deprecated // unbounded — replaced by the §2f-compliant paginated inbox (listSpec/listPage)
    List<Notification> findByPersonIdOrderByCreatedAtDesc(UUID personId);

    boolean existsByPersonIdAndTypeAndResourceId(UUID personId, String type, UUID resourceId);

    /** Self-service inbox semantics: one click marks the whole inbox read (queue is the truth). */
    @Modifying
    @Query("update Notification n set n.readAt = :at where n.personId = :personId and n.readAt is null")
    int markAllRead(@Param("personId") UUID personId, @Param("at") Instant at);

    @Query("select count(n) from Notification n where n.personId = :personId and n.readAt is null")
    long countUnread(@Param("personId") UUID personId);

    /** Pending = not acted upon and not snoozed right now (V38) — the bell badge. */
    @Query("""
        select count(n) from Notification n
        where n.personId = :personId and n.resolvedAt is null
          and (n.snoozedUntil is null or n.snoozedUntil <= :now)
        """)
    long countPending(@Param("personId") UUID personId, @Param("now") Instant now);

    /** Every person's pointer at one resource stops being pending (approval decided). */
    @Modifying
    @Query("""
        update Notification n set n.resolvedAt = :at
        where n.resourceType = :resourceType and n.resourceId = :resourceId and n.resolvedAt is null
        """)
    int resolveForResource(@Param("resourceType") String resourceType, @Param("resourceId") UUID resourceId,
                           @Param("at") Instant at);

    /** Owner-scoped single-row writes: person id in the predicate, so another person's id matches nothing. */
    @Modifying
    @Query("""
        update Notification n set n.snoozedUntil = :until
        where n.id = :id and n.personId = :personId and n.resolvedAt is null
        """)
    int snooze(@Param("id") UUID id, @Param("personId") UUID personId, @Param("until") Instant until);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.id = :id and n.personId = :personId and n.readAt is null")
    int markRead(@Param("id") UUID id, @Param("personId") UUID personId, @Param("at") Instant at);

    @Modifying
    @Query("""
        update Notification n set n.resolvedAt = :at, n.readAt = coalesce(n.readAt, :at)
        where n.id = :id and n.personId = :personId and n.resolvedAt is null
        """)
    int dismiss(@Param("id") UUID id, @Param("personId") UUID personId, @Param("at") Instant at);

    java.util.Optional<Notification> findByIdAndPersonId(UUID id, UUID personId);

    /** Push queue cursor: rows older than the settle window that no dispatcher has claimed. */
    @Query("""
        select n from Notification n
        where n.pushedAt is null and n.createdAt < :notNewerThan and n.pushAttempts < 3
        order by n.createdAt asc
        """)
    List<Notification> findPushPending(@Param("notNewerThan") Instant notNewerThan,
                                       org.springframework.data.domain.Pageable page);

    /** Claim: atomic move off NULL — safe if a future multi-replica dispatcher races. */
    @Modifying
    @Query("update Notification n set n.pushedAt = :at, n.pushVia = :via where n.id = :id and n.pushedAt is null")
    int claimPushed(@Param("id") UUID id, @Param("at") Instant at, @Param("via") String via);

    /** Bounded retry: bump attempts, leave the row queued. */
    @Modifying
    @Query("update Notification n set n.pushAttempts = n.pushAttempts + 1 where n.id = :id")
    int bumpAttempts(@Param("id") UUID id);

    // ---------- email / sms channel cursors (V29) — identical pattern to push ----------

    @Query("""
        select n from Notification n
        where n.emailSentAt is null and n.createdAt < :notNewerThan and n.emailAttempts < 3
        order by n.createdAt asc
        """)
    List<Notification> findEmailPending(@Param("notNewerThan") Instant notNewerThan,
                                        org.springframework.data.domain.Pageable page);

    /** Claim: atomic move off NULL — safe if a future multi-replica dispatcher races. */
    @Modifying
    @Query("update Notification n set n.emailSentAt = :at, n.emailVia = :via where n.id = :id and n.emailSentAt is null")
    int claimEmailed(@Param("id") UUID id, @Param("at") Instant at, @Param("via") String via);

    @Modifying
    @Query("update Notification n set n.emailAttempts = n.emailAttempts + 1 where n.id = :id")
    int bumpEmailAttempts(@Param("id") UUID id);

    @Query("""
        select n from Notification n
        where n.smsSentAt is null and n.createdAt < :notNewerThan and n.smsAttempts < 3
        order by n.createdAt asc
        """)
    List<Notification> findSmsPending(@Param("notNewerThan") Instant notNewerThan,
                                      org.springframework.data.domain.Pageable page);

    @Modifying
    @Query("update Notification n set n.smsSentAt = :at, n.smsVia = :via where n.id = :id and n.smsSentAt is null")
    int claimSmsSent(@Param("id") UUID id, @Param("at") Instant at, @Param("via") String via);

    @Modifying
    @Query("update Notification n set n.smsAttempts = n.smsAttempts + 1 where n.id = :id")
    int bumpSmsAttempts(@Param("id") UUID id);
}
