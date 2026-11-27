package com.fsp.core.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    List<PushSubscription> findByPersonIdAndStatusOrderByCreatedAtAsc(UUID personId, String status);

    boolean existsByEndpoint(String endpoint);

    boolean existsByPlatformAndToken(String platform, String token);

    java.util.Optional<PushSubscription> findByEndpointAndStatus(String endpoint, String status);

    java.util.Optional<PushSubscription> findByPlatformAndTokenAndStatus(String platform, String token, String status);

    @Modifying
    @Query("update PushSubscription s set s.status = 'DEAD', s.lastPushAt = :at where s.id = :id and s.status = 'ACTIVE'")
    int retire(@Param("id") UUID id, @Param("at") java.time.Instant at);

    @Modifying
    @Query("update PushSubscription s set s.lastPushAt = :at where s.id = :id")
    int touch(@Param("id") UUID id, @Param("at") java.time.Instant at);
}
