package com.fsp.core.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Auth-plane dispatch queue cursor — the identical claim pattern as F-12 channels (V29/V30). */
public interface AuthDispatchRepository extends JpaRepository<AuthDispatch, UUID> {

    /** Pending cursor: rows older than the settle window, not yet claimed, under the attempt cap. */
    @Query("""
        select d from AuthDispatch d
        where d.sentAt is null and d.createdAt < :notNewerThan and d.attempts < 3
        order by d.createdAt asc
        """)
    List<AuthDispatch> findPending(@Param("notNewerThan") Instant notNewerThan,
                                   org.springframework.data.domain.Pageable page);

    /** Claim: atomic move off NULL — safe if a future multi-replica dispatcher races.
     *  Delivers AND purges the secret-bearing text in one statement. */
    @Modifying
    @Query("""
        update AuthDispatch d set d.sentAt = :at, d.via = :via, d.text = ''
            where d.id = :id and d.sentAt is null
        """)
    int claim(@Param("id") UUID id, @Param("at") Instant at, @Param("via") String via);

    /** Bounded retry: bump attempts, leave the row queued (text retained for the retry). */
    @Modifying
    @Query("update AuthDispatch d set d.attempts = d.attempts + 1 where d.id = :id")
    int bumpAttempts(@Param("id") UUID id);

    /** Once the attempts cap is crossed, stop tracking the row AND purge its secret-bearing
     *  text atomically (cursor never visits attempts>=3 rows again — they must not keep OTPs). */
    @Modifying
    @Query("""
        update AuthDispatch d set d.sentAt = :at, d.via = :via, d.text = ''
            where d.id = :id and d.sentAt is null and d.attempts >= 3
        """)
    int claimExhausted(@Param("id") UUID id, @Param("at") Instant at, @Param("via") String via);
}
