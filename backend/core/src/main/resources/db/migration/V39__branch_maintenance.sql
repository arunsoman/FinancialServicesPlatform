-- BRANCH MAINTENANCE (Role Management → Branch; widget org.branches).
--
-- Branches are now created, renamed and deleted through four-eyes (BRANCH_CREATE / BRANCH_UPDATE /
-- BRANCH_DELETE approvals, checker needs role.manage + the checker flag). A filed creation is a
-- PENDING row until a different person approves it (→ ACTIVE) or rejects it (→ REJECTED, kept as
-- evidence); an approved deletion archives the row (never hard-deleted). Renames and deletions
-- change nothing until approved — the proposal lives on the approval row.

-- 1. lifecycle states
ALTER TABLE branches DROP CONSTRAINT branches_status_check;
ALTER TABLE branches ADD CONSTRAINT branches_status_check
    CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'ARCHIVED'));

-- 2. a name is unique among the LIVE branches of an organization only (case-insensitive): the name
--    of a rejected or deleted branch may be used again. The index is the authority under
--    concurrent filings / approvals (AGENTS §2d partial unique index); the service checks first
--    to give a clear 409.
ALTER TABLE branches DROP CONSTRAINT branches_tenant_name_uq;
CREATE UNIQUE INDEX ux_branches_tenant_live_name ON branches (tenant_id, lower(name))
    WHERE status IN ('PENDING', 'ACTIVE');

-- 3. who filed the branch and who decided its creation (null on system-seeded rows, e.g. V35 HO)
ALTER TABLE branches ADD COLUMN maker_id        UUID         NULL REFERENCES persons(id);
ALTER TABLE branches ADD COLUMN checker_id      UUID         NULL REFERENCES persons(id);
ALTER TABLE branches ADD COLUMN decided_at      TIMESTAMPTZ  NULL;
ALTER TABLE branches ADD COLUMN decision_reason VARCHAR(500) NULL;

-- 4. server-assigned branch codes (BR-0001 …): one global sequence, so codes never collide and
--    never need a read-then-write race per organization
CREATE SEQUENCE branch_code_seq START WITH 1 INCREMENT BY 1;
