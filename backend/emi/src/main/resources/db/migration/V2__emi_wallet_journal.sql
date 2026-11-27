-- EMI wallet journal (chapter 13 Stage 2): JE metadata rows + daily redemption caps.
-- Money truth stays in TigerBeetle (book 805/806 invariant); this table is the
-- idempotency + audit anchor that makes every TB transfer replay-safe.

-- One row per movement. (kind, reference) unique: a bank statement ref can credit a
-- wallet exactly once; a redemption has its own deterministic reference.
CREATE TABLE wallet_journal (
    id              UUID PRIMARY KEY,
    wallet_id       UUID NOT NULL REFERENCES wallet(id),
    kind            TEXT NOT NULL CHECK (kind IN ('DEPOSIT', 'REDEMPTION', 'ADJUSTMENT')),
    currency        CHAR(3) NOT NULL CHECK (currency = 'NAD'),
    amount_minor    NUMERIC(20,0) NOT NULL CHECK (amount_minor > 0),
    reference       TEXT,                    -- bank statement ref (DEPOSIT); else NULL
    tb_transfer_id  UUID NOT NULL,           -- deterministic (Uuid5); same id on every retry
    state           TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'BOOKED', 'FAILED')),
    failure_code    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0,
    UNIQUE (kind, reference)
);
CREATE INDEX idx_journal_wallet_time ON wallet_journal(wallet_id, created_at);

-- Daily redemption turnover (PSD-3 §13.1 micro envelope; cap is config, invariant is data).
CREATE TABLE wallet_daily_turnover (
    wallet_id      UUID NOT NULL REFERENCES wallet(id),
    turn_date      DATE NOT NULL,
    redeemed_minor NUMERIC(20,0) NOT NULL DEFAULT 0 CHECK (redeemed_minor >= 0),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (wallet_id, turn_date)
);
