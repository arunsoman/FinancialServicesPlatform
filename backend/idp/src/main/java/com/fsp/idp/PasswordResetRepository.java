package com.fsp.idp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {

    Optional<PasswordReset> findFirstByTokenHashAndConsumedAtIsNullOrderByCreatedAtDesc(String tokenHash);

    /** V36: the live activation of a person (at most one live row per person — V30 partial unique). */
    Optional<PasswordReset> findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(UUID personId, String purpose);

    /** Supersede: a newly requested reset invalidates all pending rows for the person. */
    @Modifying
    @Query("update PasswordReset r set r.consumedAt = :at where r.personId = :personId and r.consumedAt is null")
    int consumePending(@Param("personId") UUID personId, @Param("at") Instant at);

    @Modifying
    @Query("update PasswordReset r set r.attempts = r.attempts + 1 where r.id = :id")
    int bumpAttempts(@Param("id") UUID id);
}
