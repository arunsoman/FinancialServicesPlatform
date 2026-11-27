-- Stage 3 (chapter 13): bill-payment saga audit trail + biller REST endpoint config.
-- Plan §5.3: every saga transition is audited; four-eyes force-void carries maker/checker.

-- Append-only saga event trail: rows are created by the saga engine, never updated
-- or deleted (platform §2.5 — audit-at-boundary; EMI-local, no core imports).
CREATE TABLE saga_event (
    id                UUID PRIMARY KEY,
    saga_id           UUID NOT NULL REFERENCES bill_payment_saga(id),
    event_type        TEXT NOT NULL,
    from_state        TEXT,
    to_state          TEXT,
    actor_person_id   UUID, -- human maker (NULL for machine-triggered events)
    checker_person_id UUID, -- human checker (four-eyes force-void only)
    detail            TEXT, -- reason code; MUST NOT contain account numbers or PII
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_saga_event_saga ON saga_event(saga_id, created_at);

-- Biller REST endpoint base URL: config data, not a secret — credentials stay
-- env-name-only (endpoint_credential_env_ref; platform rule: zero secret retention).
ALTER TABLE biller ADD COLUMN endpoint_url TEXT;
