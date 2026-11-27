package com.fsp.core.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    List<BankAccount> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<BankAccount> findByTenantIdAndStatus(UUID tenantId, String status);

    /** Demote every other ACTIVE settlement account when one is activated (single-settlement invariant). */
    @Modifying
    @Query("UPDATE BankAccount b SET b.status = 'REPLACED', b.version = b.version + 1 " +
           "WHERE b.tenantId = :tenantId AND b.status = 'ACTIVE'")
    int demoteActive(UUID tenantId);
}
