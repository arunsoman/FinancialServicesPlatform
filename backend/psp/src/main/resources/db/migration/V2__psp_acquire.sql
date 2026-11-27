-- PSP Stage 5 — merchant acquiring sagas, exactly-once rail-call ledger, audit events.
-- Clearing/settlement/statement tables already exist from V1 (plan §11 Stage 0).

-- One acquiring saga: rail authorization → TB capture (pending chain) → settlement sweep.
CREATE TABLE acquisition (
    id               UUID PRIMARY KEY,
    merchant_id      UUID NOT NULL REFERENCES merchant(id),
    tenant_id        UUID NOT NULL,
    connector_code   TEXT NOT NULL,
    currency         CHAR(3) NOT NULL,
    gross_minor      NUMERIC(20,0) NOT NULL,
    fee_minor        NUMERIC(20,0) NOT NULL DEFAULT 0,
    net_minor        NUMERIC(20,0) NOT NULL,
    psp_ref          TEXT NOT NULL,               -- merchant's own order reference
    rail_ref         TEXT,                        -- scheme authorization reference
    state            TEXT NOT NULL DEFAULT 'CREATED'
        CHECK (state IN ('CREATED','AUTHORIZED','CAPTURED','SETTLED','VOIDED','REJECTED')),
    idempotency_key  TEXT NOT NULL,
    cycle_date       DATE,                        -- capture date; settlement sweep groups on it
    failure_code     TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT NOT NULL DEFAULT 0,
    UNIQUE (merchant_id, psp_ref),
    UNIQUE (idempotency_key)
);
CREATE INDEX idx_acquisition_merchant ON acquisition(merchant_id, created_at DESC);
CREATE INDEX idx_acquisition_sweep    ON acquisition(connector_code, currency, cycle_date, state);

-- Exactly-once rail-call ledger (mirror of EMI connector_call): one row per logical
-- connector invocation; replays return the recorded outcome instead of re-sending.
CREATE TABLE rail_call (
    id             UUID PRIMARY KEY,
    kind           TEXT NOT NULL,                 -- RAIL_AUTHORIZE / RAIL_PAYOUT / STATEMENT_FETCH
    call_key       TEXT NOT NULL UNIQUE,          -- deterministic: kind + saga + idempotency
    connector_code TEXT NOT NULL,
    merchant_id    UUID,
    state          TEXT NOT NULL DEFAULT 'RECORDED'
        CHECK (state IN ('RECORDED','PENDING','ACKED','REJECTED','FAILED')),
    rail_ref       TEXT,
    failure_kind   TEXT,
    failure_code   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only audit events at the service boundary (platform §7.1 PSP copy).
CREATE TABLE psp_event (
    id             BIGSERIAL PRIMARY KEY,
    acquisition_id UUID,
    merchant_id    UUID,
    kind           TEXT NOT NULL,
    from_state     TEXT,
    to_state       TEXT,
    actor          TEXT,
    detail         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_psp_event_acquisition ON psp_event(acquisition_id, id);
