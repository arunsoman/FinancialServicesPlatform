-- V31 — Optimistic locking for push_subscriptions (code-review finding, AGENTS §2.8)
--
-- Every other mutable aggregate in the platform carries persons/table version columns;
-- push_subscriptions (mutable: status, last_push_at, key-material refresh on
-- re-subscription) was the outlier. The re-subscribe path does load → mutate → save;
-- without a @Version column a concurrent retire/refresh could lost-update on the row.
-- Versioning the row makes the entity writes compare-and-swap like every other table.

ALTER TABLE fsp_notifications.push_subscriptions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
