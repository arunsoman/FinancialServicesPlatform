-- V1: fsp_plugin_inventory schema (plans/inventory-plugin-plan.md).
-- The plugin owns its own DB; core's DB and TigerBeetle are NEVER touched from here.
-- Money = minor units as bigint (string on the wire); currency ISO-4217 alpha-3.
-- Every table carries owner_tenant_id; all queries are tenant-scoped (plan §2/§12.3).

-- ---- physical plane ----
CREATE TABLE inventory_products (
    id                    UUID PRIMARY KEY,
    owner_tenant_id       UUID NOT NULL,
    code                  VARCHAR(64) NOT NULL,          -- tenant-unique SKU code
    name                  VARCHAR(200) NOT NULL,
    unit                  VARCHAR(16) NOT NULL DEFAULT 'EA',
    unit_cost_minor       BIGINT NOT NULL,               -- maker-entered cost
    wholesale_price_minor BIGINT NOT NULL,
    rrp_minor             BIGINT NOT NULL,
    currency              VARCHAR(3) NOT NULL,
    status                VARCHAR(32) NOT NULL,          -- PENDING_APPROVAL|ACTIVE|REJECTED
    open_approval_id      UUID,                          -- PLUGIN:SKU_PRICING row while pending
    created_by            UUID NOT NULL,
    approved_by           UUID,
    version               BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMP NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT inventory_products_tenant_code_uq UNIQUE (owner_tenant_id, code)
);

CREATE TABLE inventory_stock_batches (
    id               UUID PRIMARY KEY,
    owner_tenant_id  UUID NOT NULL,
    product_id       UUID NOT NULL REFERENCES inventory_products (id),
    sku_code         VARCHAR(64) NOT NULL,                -- snapshot of the product code
    sku_name         VARCHAR(200) NOT NULL,
    currency         VARCHAR(3) NOT NULL,
    quantity         INT NOT NULL CHECK (quantity > 0),
    unit_cost_minor  BIGINT NOT NULL,
    total_cost_minor BIGINT NOT NULL,
    reference        VARCHAR(120) NOT NULL,
    note             VARCHAR(1000),
    status           VARCHAR(32) NOT NULL,                -- STAGED|COMMITTED|REJECTED
    journal_entry_id UUID,                                -- balanced JE (Dr 1200 / Cr 2010)
    approval_id      UUID,                                -- PLUGIN:STOCK_RECEIPT row
    received_by      UUID NOT NULL,
    approved_by      UUID,
    version          BIGINT NOT NULL DEFAULT 0,
    received_at      TIMESTAMP NOT NULL DEFAULT now(),
    decided_at       TIMESTAMP,
    CONSTRAINT inventory_stock_batches_tenant_ref_uq UNIQUE (owner_tenant_id, reference)
);
CREATE INDEX inventory_stock_batches_tenant_idx ON inventory_stock_batches (owner_tenant_id, status, received_at DESC);
CREATE INDEX inventory_stock_batches_product_idx ON inventory_stock_batches (product_id, status);

CREATE TABLE inventory_stock_movements (
    id             UUID PRIMARY KEY,
    owner_tenant_id UUID NOT NULL,
    batch_id       UUID NOT NULL REFERENCES inventory_stock_batches (id),
    product_id     UUID NOT NULL,
    direction      VARCHAR(16) NOT NULL,                  -- RECEIPT|CONSUME|DISPOSE
    quantity       INT NOT NULL,
    reference      VARCHAR(120),
    actor_id       UUID,
    moved_at       TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX inventory_stock_movements_batch_idx ON inventory_stock_movements (batch_id, moved_at DESC);

-- ---- digital plane ----
CREATE TABLE digital_products (
    id              UUID PRIMARY KEY,
    owner_tenant_id UUID NOT NULL,
    code            VARCHAR(64) NOT NULL,                 -- e.g. TNM-DATA-1GB
    name            VARCHAR(200) NOT NULL,
    kind            VARCHAR(32) NOT NULL DEFAULT 'VOUCHER',
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',-- ACTIVE|SUSPENDED
    sale_price_minor  BIGINT NOT NULL,                    -- retail face the customer pays
    cost_price_minor  BIGINT NOT NULL,                    -- wholesale face paid on float funding
    currency        VARCHAR(3) NOT NULL,
    telco_bp        INT NOT NULL DEFAULT 9400,            -- share of face to the telco (basis points)
    distributor_bp  INT NOT NULL DEFAULT 200,             -- share to the distributor
    platform_bp     INT NOT NULL DEFAULT 100,             -- platform fee revenue
    margin_gl_code  INT NOT NULL DEFAULT 4000,            -- merchant-margin income GL (remainder)
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT digital_products_tenant_code_uq UNIQUE (owner_tenant_id, code)
);

CREATE TABLE voucher_batches (
    id                UUID PRIMARY KEY,
    owner_tenant_id   UUID NOT NULL,
    digital_product_id UUID NOT NULL REFERENCES digital_products (id),
    product_code      VARCHAR(64) NOT NULL,               -- snapshot
    currency          VARCHAR(3) NOT NULL,
    unit_face_minor   BIGINT NOT NULL,
    voucher_count     INT NOT NULL CHECK (voucher_count > 0),
    reference         VARCHAR(120) NOT NULL,
    status            VARCHAR(32) NOT NULL,               -- PENDING_CLEARANCE|ACTIVE|EXHAUSTED
    approval_id       UUID,                               -- PLUGIN:VOUCHER_RELEASE row
    journal_entry_id  UUID,                               -- no money on upload; set if a JE is used
    uploaded_by       UUID NOT NULL,
    authorized_by     UUID,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    authorized_at     TIMESTAMP,
    CONSTRAINT voucher_batches_tenant_ref_uq UNIQUE (owner_tenant_id, reference)
);
CREATE INDEX voucher_batches_tenant_idx ON voucher_batches (owner_tenant_id, status, created_at DESC);

CREATE TABLE vouchers (
    id               UUID PRIMARY KEY,
    owner_tenant_id  UUID NOT NULL,
    batch_id         UUID NOT NULL REFERENCES voucher_batches (id),
    digital_product_id UUID NOT NULL,
    encrypted_pin    BYTEA NOT NULL,                      -- AES-256-GCM(KEK→HKDF(batch), D6)
    pin_nonce        BYTEA NOT NULL,
    pin_tag          BYTEA NOT NULL,
    masked_pin       VARCHAR(32) NOT NULL,                -- display only; plaintext NEVER stored
    face_value_minor BIGINT NOT NULL,
    currency         VARCHAR(3) NOT NULL,
    status           VARCHAR(32) NOT NULL,                -- AVAILABLE|RESERVED|RELEASED
    reserved_at      TIMESTAMP,
    released_at      TIMESTAMP,
    release_sale_id  UUID
);
CREATE INDEX vouchers_batch_status_idx ON vouchers (batch_id, status) WHERE status = 'AVAILABLE';
CREATE UNIQUE INDEX vouchers_release_sale_uq ON vouchers (release_sale_id) WHERE release_sale_id IS NOT NULL;

CREATE TABLE voucher_sales (
    id                UUID PRIMARY KEY,
    owner_tenant_id   UUID NOT NULL,
    digital_product_id UUID NOT NULL,
    voucher_id        UUID NOT NULL REFERENCES vouchers (id),
    sale_reference    VARCHAR(120) NOT NULL,              -- client dedupe reference
    channel           VARCHAR(32) NOT NULL DEFAULT 'POS',
    amount_minor      BIGINT NOT NULL,                    -- money moved (sale price)
    currency          VARCHAR(3) NOT NULL,
    journal_entry_id  UUID NOT NULL,                      -- intent JE that moved the money
    dispensed_by      UUID NOT NULL,
    dispensed_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT voucher_sales_tenant_ref_uq UNIQUE (owner_tenant_id, sale_reference)
);

CREATE TABLE float_deposits (
    id               UUID PRIMARY KEY,
    owner_tenant_id  UUID NOT NULL,
    float_code       VARCHAR(64) NOT NULL,                -- merchant float account reference
    currency         VARCHAR(3) NOT NULL,
    amount_minor     BIGINT NOT NULL CHECK (amount_minor > 0),
    note             VARCHAR(1000),
    status           VARCHAR(32) NOT NULL,                -- PENDING_APPROVAL|COMMITTED|REJECTED
    journal_entry_id UUID,                                -- Dr 1000 Cash / Cr 2050 float
    approval_id      UUID,
    requested_by     UUID NOT NULL,
    approved_by      UUID,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    decided_at       TIMESTAMP
);
CREATE INDEX float_deposits_tenant_idx ON float_deposits (owner_tenant_id, status, created_at DESC);

-- ---- cross-plane ----
CREATE TABLE inventory_events (
    id             UUID PRIMARY KEY,
    owner_tenant_id UUID NOT NULL,
    event_type     VARCHAR(64) NOT NULL,                  -- SKU_PRICED|BATCH_COMMITTED|...
    subject_type   VARCHAR(32) NOT NULL,
    subject_id     UUID NOT NULL,
    payload        JSONB NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX inventory_events_subject_idx ON inventory_events (subject_id, created_at DESC);

-- plugin-level idempotency (AGENTS §9.1): unique on (person_id, request_key)
CREATE TABLE idempotency_records (
    id                   UUID PRIMARY KEY,
    person_id            UUID NOT NULL,
    request_key          VARCHAR(200) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    response_status      INT,
    response_content_type VARCHAR(100),
    response_body        BYTEA,
    created_at           TIMESTAMP NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_records_person_key_uq UNIQUE (person_id, request_key)
);
