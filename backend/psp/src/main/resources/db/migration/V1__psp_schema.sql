-- PSP service schema — chapter 13 Stage 0 (plan §11).
-- Dedicated database `fsp_psp` (separate Boot process :8085, separate Flyway history — R5 isolation).
-- Business model: pass-through acquiring — in-flight funds in nominal clearing positions
-- that MUST be zero every settlement cycle (never customer-facing wallets).

-- Bank connector profile rows (plan §3.4.3): a new bank is a ROW, never code.
CREATE TABLE bank_connector (
    id                         UUID PRIMARY KEY,
    bank_code                  TEXT NOT NULL UNIQUE,
    bank_name                  TEXT NOT NULL,
    rail_family                TEXT NOT NULL,
    transport_channel          TEXT NOT NULL,
    message_format             TEXT NOT NULL,
    dictionary_version         TEXT,
    charset                    TEXT NOT NULL DEFAULT 'UTF-8',
    connect_timeout_ms         INT NOT NULL DEFAULT 2000,
    read_timeout_ms            INT NOT NULL DEFAULT 8000,
    overall_timeout_ms         INT NOT NULL DEFAULT 15000,
    endpoint_credential_env_ref TEXT NOT NULL,      -- env-var name; secrets never in this DB
    scheme_limits              JSONB,
    settlement_window          TEXT,
    statement_cadence          TEXT,
    idempotency_convention     TEXT,
    profile_status             TEXT NOT NULL DEFAULT 'DRAFT'
        CHECK (profile_status IN ('DRAFT', 'SANDBOX_CONFORMED', 'ENABLED')),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                    BIGINT NOT NULL DEFAULT 0
);

-- Merchants acquiring through the PSP.
CREATE TABLE merchant (
    id                         UUID PRIMARY KEY,
    tenant_id                  UUID NOT NULL,
    merchant_code              TEXT NOT NULL UNIQUE,
    display_name               TEXT NOT NULL,
    status                     TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
    pay_over_days              INT NOT NULL DEFAULT 2,
    merchant_bank_account_ref  TEXT,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                    BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_merchant_tenant ON merchant(tenant_id);

-- Nominal clearing positions: in-flight funds, per (connector, currency, day).
-- Invariant (recon): sum to ZERO at each settlement cycle (plan §1.1).
CREATE TABLE clearing_position (
    id             UUID PRIMARY KEY,
    connector_code TEXT NOT NULL,
    currency       CHAR(3) NOT NULL,
    position_date  DATE NOT NULL,
    tb_account_id  UUID NOT NULL,
    amount_minor   NUMERIC(20,0) NOT NULL DEFAULT 0,
    settled        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        BIGINT NOT NULL DEFAULT 0,
    UNIQUE (connector_code, currency, position_date)
);

-- Merchant payable accruals (PSD-1 §12.2.3: pay-over within 2 business days).
CREATE TABLE merchant_payable (
    id           UUID PRIMARY KEY,
    merchant_id  UUID NOT NULL REFERENCES merchant(id),
    currency     CHAR(3) NOT NULL,
    period_start DATE NOT NULL,
    period_end   DATE NOT NULL,
    accrual_minor  NUMERIC(20,0) NOT NULL DEFAULT 0,
    paid_minor     NUMERIC(20,0) NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT NOT NULL DEFAULT 0,
    UNIQUE (merchant_id, currency, period_start, period_end)
);

-- Settlement sweep batches.
CREATE TABLE settlement_batch (
    id             UUID PRIMARY KEY,
    connector_code TEXT NOT NULL,
    currency       CHAR(3) NOT NULL,
    window_start   TIMESTAMPTZ NOT NULL,
    window_end     TIMESTAMPTZ NOT NULL,
    state          TEXT NOT NULL DEFAULT 'OPEN'
        CHECK (state IN ('OPEN', 'SWEEPING', 'COMPLETED', 'FAILED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        BIGINT NOT NULL DEFAULT 0
);

-- Statement/recon ingestion journal: hash-claim-once, partial-file tolerant (plan §3.4.4).
CREATE TABLE statement_ingest (
    id             UUID PRIMARY KEY,
    connector_code TEXT NOT NULL,
    source          TEXT NOT NULL,
    file_hash      TEXT NOT NULL UNIQUE,          -- sha256; a replay re-ingests nothing
    rows_total      INT NOT NULL DEFAULT 0,
    rows_matched    INT NOT NULL DEFAULT 0,
    rows_mismatched INT NOT NULL DEFAULT 0,
    ingested_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Own transactional outbox (exactly-once layer 4).
CREATE TABLE psp_outbox (
    id           UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    kind         TEXT NOT NULL,
    payload_json JSONB NOT NULL,
    attempts     INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at   TIMESTAMPTZ
);
CREATE INDEX idx_psp_outbox_pending ON psp_outbox(claimed_at, created_at);
