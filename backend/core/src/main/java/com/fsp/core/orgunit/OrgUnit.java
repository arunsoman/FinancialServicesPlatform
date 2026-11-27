package com.fsp.core.orgunit;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * An organization unit a role is anchored to (a branch or a department). Org-scoped master data:
 * never hard-deleted (ARCHIVED), optimistic-locked (AGENTS §2d).
 */
@MappedSuperclass
public abstract class OrgUnit {

    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";

    @Id
    public UUID id;
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;
    @Column(nullable = false)
    public String code;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String status;
    @Version
    @Column(nullable = false)
    public long version;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
