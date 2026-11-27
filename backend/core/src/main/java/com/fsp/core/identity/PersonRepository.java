package com.fsp.core.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persons. Collection reads go through {@link JpaSpecificationExecutor#findAll(
 * org.springframework.data.jpa.domain.Specification, org.springframework.data.domain.Pageable)}
 * so search / filter / sort / paging all execute in PostgreSQL (AGENTS §2f).
 */
public interface PersonRepository extends JpaRepository<Person, UUID>, JpaSpecificationExecutor<Person> {

    Optional<Person> findByUsernameIgnoreCase(String username);

    boolean existsByUsername(String username);

    /** V36: a live (non-archived) customer already holds this email (case-insensitive). */
    @Query("select count(p) > 0 from Person p where p.isCustomer = true and p.status <> 'ARCHIVED' and lower(p.email) = lower(:email)")
    boolean existsLiveCustomerEmail(@Param("email") String email);

    /** V36: a live customer already holds this E.164 phone. */
    @Query("select count(p) > 0 from Person p where p.isCustomer = true and p.status <> 'ARCHIVED' and p.phone = :phone")
    boolean existsLiveCustomerPhone(@Param("phone") String phone);

    /** One row per status of an organization's persons — the user-list filter counts. */
    @Query("select p.status as status, count(p) as n from Person p where p.tenantId = :tenantId and p.isCustomer = false group by p.status")
    List<StatusCount> countByStatus(@Param("tenantId") UUID tenantId);

    interface StatusCount {
        String getStatus();
        long getN();
    }
}
