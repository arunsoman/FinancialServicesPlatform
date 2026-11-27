package com.fsp.psp.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PspOutboxRepository extends JpaRepository<PspOutbox, UUID> {

    /** Unclaimed, oldest-first, bounded (platform §2f — no unbounded fetches). */
    @Query("SELECT o FROM PspOutbox o WHERE o.claimedAt IS NULL ORDER BY o.createdAt ASC")
    List<PspOutbox> findUnclaimed(Pageable pageable);
}
