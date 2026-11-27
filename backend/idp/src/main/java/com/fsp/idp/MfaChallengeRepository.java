package com.fsp.idp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, UUID> {

    Optional<MfaChallenge> findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
        UUID personId, String purpose);

    /** Consume all pending challenges for the person+purpose (supersede on new issuance / too many attempts). */
    @Modifying
    @Query("update MfaChallenge c set c.consumedAt = :at where c.personId = :personId and c.purpose = :purpose and c.consumedAt is null")
    int consumePending(@Param("personId") UUID personId, @Param("purpose") String purpose, @Param("at") Instant at);

    @Modifying
    @Query("update MfaChallenge c set c.attempts = c.attempts + 1 where c.id = :id")
    int bumpAttempts(@Param("id") UUID id);
}
