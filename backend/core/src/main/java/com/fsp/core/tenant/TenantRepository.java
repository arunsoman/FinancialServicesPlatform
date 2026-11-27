package com.fsp.core.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    List<Tenant> findByParentTenantId(UUID parentId);

    /** Server-side page of an org's children (AGENTS §2.7) — paging, sort and search run in the DB. */
    org.springframework.data.domain.Page<Tenant> findByParentTenantId(
        UUID parentId, org.springframework.data.domain.Pageable page);

    org.springframework.data.domain.Page<Tenant> findByParentTenantIdAndLegalNameContainingIgnoreCase(
        UUID parentId, String legalName, org.springframework.data.domain.Pageable page);
}
