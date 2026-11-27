package com.fsp.core.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    public UUID id;
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;
    @Column(nullable = false)
    public String key;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String scope; // PLATFORM | TENANT | SUBTREE | EDGE
    @Column(nullable = false)
    public long version;
    /** What the role is for (Add Role form: Role Description). */
    @Column
    public String description;
    @Column(name = "branch_id")
    public UUID branchId;
    @Column(name = "department_id")
    public UUID departmentId;
}
