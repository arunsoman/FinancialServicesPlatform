package com.fsp.core.orgunit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A branch of an organization (V34, lifecycle V39). Created, renamed and deleted through four-eyes
 * ({@link BranchService}): PENDING (filed) → ACTIVE (approved) | REJECTED; ACTIVE → ARCHIVED (deleted).
 */
@Entity
@Table(name = "branches")
public class Branch extends OrgUnit {

    public static final String PENDING = "PENDING";
    public static final String REJECTED = "REJECTED";

    /** person who filed the branch; null on system-seeded rows */
    @Column(name = "maker_id")
    public UUID makerId;
    /** person who decided the creation (approved or rejected) */
    @Column(name = "checker_id")
    public UUID checkerId;
    @Column(name = "decided_at")
    public Instant decidedAt;
    @Column(name = "decision_reason")
    public String decisionReason;
}
