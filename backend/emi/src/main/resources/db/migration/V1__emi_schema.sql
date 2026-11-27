-- EMI service schema — chapter 13 Stage 0 (plan §11).
-- Dedicated database `fsp_emi` (separate Boot process :8086, separate Flyway history — R5 isolation).
-- Money: minor units only, NUMERIC(20,0); currency ISO-4217 alpha-3.
-- Mutable aggregates carry @Version (platform §2.8).

-- Customer wallets. PSD-3 §9.1.1: regulated e-money balances are NAD-only, redeemable at par.
CREATE TABLE wallet (
    id                 UUID PRIMARY KEY,
    wallet_ref         TEXT NOT NULL UNIQUE,
    person_id          UUID NOT NULL,
    currency           CHAR(3) NOT NULL CHECK (currency = 'NAD'),
    status             TEXT NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    daily_limit_minor  NUMERIC(20,0) NOT NULL DEFAULT 500000,
    tb_account_id      UUID NOT NULL,
    opened_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_wallet_person ON wallet(person_id);

-- Biller registry. Per-biller reversal class (plan §12.3): contract-silent billers default C.
CREATE TABLE biller (
    id                              UUID PRIMARY KEY,
    code                            TEXT NOT NULL UNIQUE,
    name                            TEXT NOT NULL,
    category                        TEXT NOT NULL,
    reversal_class                  CHAR(1) NOT NULL DEFAULT 'C' CHECK (reversal_class IN ('A','B','C')),
    contract_reversal_window_seconds BIGINT,
    transport_channel               TEXT NOT NULL,
    endpoint_credential_env_ref     TEXT NOT NULL,   -- env-var name; secrets never in this DB
    probe_supported                 BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Bill-payment saga state machine (plan §5.2): one pending transfer + one row, in lockstep.
CREATE TABLE bill_payment_saga (
    id                UUID PRIMARY KEY,
    person_id         UUID NOT NULL,
    wallet_id         UUID NOT NULL REFERENCES wallet(id),
    currency          CHAR(3) NOT NULL,
    amount_minor      NUMERIC(20,0) NOT NULL CHECK (amount_minor >= 0),
    biller_id         UUID NOT NULL REFERENCES biller(id),
    account_ref       TEXT NOT NULL,
    idempotency_key   TEXT NOT NULL UNIQUE,
    state             TEXT NOT NULL CHECK (state IN
        ('HOLD', 'SUBMITTED', 'PENDING_RECOGNITION', 'CONFIRMED', 'VOIDED', 'EXPIRED', 'DISPUTED')),
    reversal_class    CHAR(1) NOT NULL DEFAULT 'C' CHECK (reversal_class IN ('A','B','C')),
    rail_ref          TEXT,
    submitted_at      TIMESTAMPTZ,
    settled_at        TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ,
    attempt           INT NOT NULL DEFAULT 0,
    last_failure_kind TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_saga_state_expires ON bill_payment_saga(state, expires_at);
CREATE INDEX idx_saga_person ON bill_payment_saga(person_id);

-- Outbound provider-call ledger: the exactly-once layer-3 record (plan §6),
-- one row per (service, kind, idempotency_key).
CREATE TABLE connector_call (
    id              UUID PRIMARY KEY,
    connector_code  TEXT NOT NULL,
    host_key        TEXT NOT NULL,
    kind            TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    rail_ref        TEXT,
    state           TEXT NOT NULL CHECK (state IN
        ('RECORDED', 'SENT', 'ACKED', 'PENDING', 'REJECTED', 'FAILED')),
    failure_kind    TEXT,
    failure_code    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0,
    UNIQUE (kind, idempotency_key)
);
CREATE INDEX idx_call_outbox_window ON connector_call(state, created_at);

-- Digital-asset ledgers (plan §6): separate TB ledgers, every asset leg mirrors a fiat leg.
-- Closed-loop-first (plan §12.1): non-transferable/non-resalable by DEFAULT — the VAA
-- Schedule-2 exclusion only holds while these columns stay false.
CREATE TABLE asset_ledger (
    id                 UUID PRIMARY KEY,
    asset_code         TEXT NOT NULL UNIQUE,
    asset_name         TEXT NOT NULL,
    ledger_kind        TEXT NOT NULL CHECK (ledger_kind IN ('INVOICE_FINANCE', 'LOYALTY', 'ESCROW', 'CUSTODIAN')),
    tb_ledger_id       UUID NOT NULL,
    fiat_mirror_tb_ledger_id UUID NOT NULL,
    closed_loop        BOOLEAN NOT NULL DEFAULT TRUE,
    transfer_allowed   BOOLEAN NOT NULL DEFAULT FALSE,
    redeemable_fiat    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            BIGINT NOT NULL DEFAULT 0
);

-- Tax rate buckets (plan §12.4): rate-bucket is four-eyes-config data, snapshotted per JE.
CREATE TABLE tax_rate_bucket (
    id        UUID PRIMARY KEY,
    code      TEXT NOT NULL UNIQUE,
    rate_bp   INT NOT NULL CHECK (rate_bp >= 0 AND rate_bp <= 10000),
    active    BOOLEAN NOT NULL DEFAULT TRUE,
    version   BIGINT NOT NULL DEFAULT 0
);

-- Own transactional outbox (exactly-once layer 4): DB row + outbox row in one transaction.
CREATE TABLE emi_outbox (
    id           UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    kind         TEXT NOT NULL,
    payload_json JSONB NOT NULL,
    attempts     INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at   TIMESTAMPTZ
);
CREATE INDEX idx_emi_outbox_pending ON emi_outbox(claimed_at, created_at);
