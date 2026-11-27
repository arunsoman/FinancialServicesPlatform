package com.fsp.core.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface NotificationChannelPrefRepository extends JpaRepository<NotificationChannelPref, NotificationChannelPrefId> {

    java.util.Optional<NotificationChannelPref> findByPersonIdAndChannel(UUID personId, String channel);

    /** Definitive bounce verdict: flag the contact dead, leave the consent row visible. */
    @Modifying
    @Query("update NotificationChannelPref p set p.dead = true, p.updatedAt = :at "
        + "where p.personId = :personId and p.channel = :channel")
    int flagDead(@Param("personId") UUID personId, @Param("channel") String channel, @Param("at") java.time.Instant at);
}
