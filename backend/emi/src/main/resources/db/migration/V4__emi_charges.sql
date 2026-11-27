-- Stage 4: SKU money (plans/bank-connectors-bill-payments-plan.md §7.1).
-- One charge row per wallet-funded order: the idempotency anchor and state machine for a
-- LINKED PENDING chain of TB transfers (DR wallet → CR sku-clearing + CR tax-liability
-- [+ CR fee-clearing when the platform fee is charged in the wallet book]).
-- The charge row is the audit truth for the fingerprint; TigerBeetle holds the money truth.
CREATE TABLE charge (
    id                UUID PRIMARY KEY,
    wallet_id         UUID         NOT NULL REFERENCES wallet(id),
    person_id         UUID         NOT NULL,
    order_ref         TEXT         NOT NULL,
    currency          CHAR(3)      NOT NULL CHECK (currency = 'NAD'),
    merch_minor       NUMERIC(20,0) NOT NULL CHECK (merch_minor >= 0),
    fee_minor         NUMERIC(20,0) NOT NULL CHECK (fee_minor >= 0),
    tax_minor         NUMERIC(20,0) NOT NULL CHECK (tax_minor >= 0),
    total_minor       NUMERIC(20,0) NOT NULL CHECK (total_minor >= 0),
    price_fingerprint CHAR(64)     NOT NULL,
    state             TEXT NOT NULL CHECK (state IN
                          ('HOLD', 'CONFIRMED', 'VOIDED', 'EXPIRED')),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_charge_wallet_order UNIQUE (wallet_id, order_ref)
);
CREATE INDEX idx_charge_state_created ON charge(state, created_at);
