-- Stage 4 — SKU money (plans/bank-connectors-bill-payments-plan.md §7.1): core order
-- header + price-fingerprint audit trail. Core is the order brain; EMI is the money
-- hands (linked pending chain over the TB wallet book); the fingerprint binds the
-- quoted breakdown at quote time — a repo price change between quote and charge is a
-- visible failure (EMI_PRICE_FINGERPRINT_MISMATCH), never a silent repricing (R1).
CREATE TABLE sku_order (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    buyer_person_id   UUID NOT NULL,
    order_ref         VARCHAR(128) NOT NULL,
    sku_code          VARCHAR(64)  NOT NULL,
    description       VARCHAR(256) NOT NULL,
    book_id           UUID,          -- mirror JE book (chargeIntent), resolved by caller
    wallet_id         UUID NOT NULL,
    currency          CHAR(3)       NOT NULL,
    merch_minor       NUMERIC(20,0) NOT NULL,
    fee_minor         NUMERIC(20,0) NOT NULL,
    tax_minor         NUMERIC(20,0) NOT NULL,
    total_minor       NUMERIC(20,0) NOT NULL,
    price_fingerprint CHAR(64)      NOT NULL,
    state             VARCHAR(20)   NOT NULL,
    charge_id         UUID,
    journal_entry_id  UUID,
    failure_code      VARCHAR(40),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT sku_order_state CHECK (state IN
        ('CREATED','CHARGE_HELD','CHARGE_CONFIRMED','CHARGE_VOIDED','CHARGE_EXPIRED','CHARGE_FAILED'))
);
CREATE UNIQUE INDEX ux_sku_order_buyer_ref ON sku_order (buyer_person_id, order_ref);
CREATE INDEX ix_sku_order_state ON sku_order (state, created_at);
