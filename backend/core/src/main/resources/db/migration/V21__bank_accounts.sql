-- V21 · F-10 — settlement bank accounts.
-- A person of an organization (maker, bank.account.create) files the organization's
-- settlement bank account; a checker of that organization (or the platform team, for the
-- root's own account) approves it — four-eyes at person_id through the standard approval
-- lane (OpType BANK_ACCOUNT_CREATE). One ACTIVE account per tenant: activating a new one
-- demotes the previous to REPLACED. Account numbers are stored in full but masked to the
-- last 4 on the wire (AGENTS §2c, never echo PII).

CREATE TABLE bank_accounts (
  id              UUID PRIMARY KEY,
  tenant_id       UUID   NOT NULL REFERENCES tenants(id),
  holder_name     TEXT   NOT NULL,        -- 2-160 chars; must name the account holder
  bank_code       TEXT   NOT NULL,        -- Namibian bank code (system master list)
  branch_code     TEXT   NOT NULL,        -- ^\d{5,6}$ (BoN registry)
  account_number  TEXT   NOT NULL,        -- ^\d{6,16}$
  account_type    TEXT   NOT NULL,        -- SETTLEMENT | CURRENT | SAVINGS
  currency        VARCHAR(3) NOT NULL,    -- ISO-4217 alpha-3
  status          TEXT   NOT NULL,        -- PENDING | ACTIVE | REPLACED | REJECTED
  approval_id     UUID REFERENCES approval_requests(id),
  version         BIGINT NOT NULL DEFAULT 0, -- optimistic lock (AGENTS §2d)
  maker_person_id UUID   NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_bank_accounts_one_active
  ON bank_accounts (tenant_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_bank_accounts_tenant ON bank_accounts (tenant_id, status);

-- bank.account.view (READ) — the list surface both planes share. Maker plane also holds
-- bank.account.create, checker plane bank.account.approve (V2 seeds). New READ key: the
-- role migration below is the deliberate grant set; born roles receive it via
-- RoleTemplates (ADMIN_BASE + CHECKER_BASE).
INSERT INTO permissions (key, title, risk, component) VALUES
  ('bank.account.view', 'View settlement bank accounts', 'READ', 'ledger')
ON CONFLICT (key) DO NOTHING;

-- Deliberate grants: platform + PSP plane templates and the organization templates.
INSERT INTO role_permissions (role_id, permission_key)
SELECT id, 'bank.account.view' FROM roles
WHERE id IN (
  '00000000-0000-0000-0000-000000000101', -- Platform Administrator
  '00000000-0000-0000-0000-0000000001a1', -- PSP Administrator
  '00000000-0000-0000-0000-0000000001a2', -- TPTS Operations
  '00000000-0000-0000-0000-0000000002a1', -- Organization Administrator
  '00000000-0000-0000-0000-0000000002a2'  -- Organization Checker
)
ON CONFLICT DO NOTHING;
