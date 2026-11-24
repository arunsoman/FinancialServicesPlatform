-- V41: invoicing (billing.invoices). An organization (seller) bills another organization
-- (buyer) on credit terms — 30 days unless the seller picks other terms. The buyer settles
-- from an e-money wallet (EMI charge, instant), by bank transfer (the seller confirms the
-- money arrived) or — once a card rail with a hosted page exists — by card.
--
-- Money: BIGINT minor units, never floating point. Totals are computed by core from the
-- lines (tax rounds half-up) and stored, so every read is a plain SQL filter/sort/sum.
-- State: DRAFT → ISSUED → PARTIALLY_PAID → PAID; DRAFT → VOID (at once);
--        ISSUED (no money) → VOID_PENDING → VOID (second person approves) | ISSUED (rejected).
-- Concurrency: optimistic @Version on invoices and payments (AGENTS §2d).

CREATE TABLE invoices (
  id                 UUID PRIMARY KEY,
  seller_tenant_id   UUID        NOT NULL REFERENCES tenants(id),
  buyer_tenant_id    UUID        NOT NULL REFERENCES tenants(id),
  number             TEXT,                          -- seller-scoped, set at issue (INV-2026-0007)
  currency           VARCHAR(3)  NOT NULL,
  planned_issue_date DATE        NOT NULL,
  issue_date         DATE,
  due_date           DATE,
  credit_days        INT         NOT NULL CHECK (credit_days BETWEEN 0 AND 365),
  subtotal_minor     BIGINT      NOT NULL CHECK (subtotal_minor >= 0),
  tax_minor          BIGINT      NOT NULL CHECK (tax_minor >= 0),
  total_minor        BIGINT      NOT NULL CHECK (total_minor > 0),
  paid_minor         BIGINT      NOT NULL DEFAULT 0 CHECK (paid_minor >= 0),
  status             TEXT        NOT NULL CHECK (status IN
                       ('DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOID_PENDING', 'VOID')),
  reference          TEXT,
  notes              TEXT,
  void_reason        TEXT,
  void_approval_id   UUID REFERENCES approval_requests(id),
  created_by         UUID        NOT NULL,
  version            BIGINT      NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CHECK (seller_tenant_id <> buyer_tenant_id),
  CHECK (paid_minor <= total_minor)
);

CREATE UNIQUE INDEX ux_invoices_seller_number ON invoices (seller_tenant_id, number) WHERE number IS NOT NULL;
CREATE INDEX ix_invoices_seller ON invoices (seller_tenant_id, status, due_date);
CREATE INDEX ix_invoices_buyer  ON invoices (buyer_tenant_id, status, due_date);
CREATE INDEX ix_invoices_created ON invoices (created_at DESC, id);

CREATE TABLE invoice_lines (
  invoice_id       UUID    NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
  line_no          INT     NOT NULL,
  description      TEXT    NOT NULL,
  quantity         INT     NOT NULL CHECK (quantity BETWEEN 1 AND 999999),
  unit_price_minor BIGINT  NOT NULL CHECK (unit_price_minor > 0),
  tax_rate_bps     INT     NOT NULL CHECK (tax_rate_bps BETWEEN 0 AND 10000),
  net_minor        BIGINT  NOT NULL,
  tax_minor        BIGINT  NOT NULL,
  total_minor      BIGINT  NOT NULL,
  PRIMARY KEY (invoice_id, line_no)
);

CREATE TABLE invoice_payments (
  id                UUID PRIMARY KEY,
  invoice_id        UUID        NOT NULL REFERENCES invoices(id),
  method            TEXT        NOT NULL CHECK (method IN ('WALLET', 'BANK_TRANSFER', 'CARD')),
  amount_minor      BIGINT      NOT NULL CHECK (amount_minor > 0),
  status            TEXT        NOT NULL CHECK (status IN
                      ('PENDING', 'AWAITING_CONFIRMATION', 'SUCCEEDED', 'FAILED', 'COMMIT_UNKNOWN')),
  reference         TEXT,
  rail_ref          TEXT,                            -- EMI charge id (WALLET); never shown in full
  failure_code      TEXT,
  payer_person_id   UUID        NOT NULL,
  confirmed_by      UUID,
  version           BIGINT      NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  confirmed_at      timestamptz
);

CREATE INDEX ix_invoice_payments_invoice ON invoice_payments (invoice_id, created_at);

-- Running numbers per seller and year; taken with one atomic UPSERT … RETURNING at issue.
CREATE TABLE invoice_number_seq (
  seller_tenant_id UUID NOT NULL REFERENCES tenants(id),
  year             INT  NOT NULL,
  last_value       INT  NOT NULL,
  PRIMARY KEY (seller_tenant_id, year)
);

-- The checker of a void: a second person of the seller organization (four-eyes at person_id).
INSERT INTO permissions (key, title, risk, component) VALUES
  ('invoice.void.approve', 'Approve voiding an issued invoice', 'FOUR_EYES', 'billing')
ON CONFLICT (key) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, 'invoice.void.approve'
FROM roles r
WHERE (r.key = 'PLATFORM_ADMIN' AND r.scope = 'PLATFORM')
   OR (r.key IN ('TENANT_ADMIN', 'TENANT_CHECKER') AND r.scope IN ('TENANT', 'SUBTREE'))
ON CONFLICT DO NOTHING;
