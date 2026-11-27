-- V29 · F-12 extension — email + SMS outbound channels (docs/architecture/12-notifications-and-push.md §12.3–§12.7)
--       · NOTIFICATION SCHEMA ISOLATION.
--
-- The whole notify subdomain (V23 tables + this migration's additions) moves into its own
-- PostgreSQL schema, `fsp_notifications`: own namespace, own tables, no default search_path
-- leakage. Core still fronts it (notify is a core module, not a plugin — no separate
-- process), but its data plane is fully fenced. Rules from here on:
--   1. Every notify DDL change is FULLY QUALIFIED as fsp_notifications.<table>.
--   2. Cross-schema FKs to persons/tenants (public) are intentional and allowed.
--   3. Nothing else in public/ may take a dependency on fsp_notifications tables (one-way
--      read coupling via JPA entities only — same rule as plugin schema isolation, §4).
CREATE SCHEMA IF NOT EXISTS fsp_notifications;
ALTER TABLE notifications SET SCHEMA fsp_notifications;
ALTER TABLE push_subscriptions SET SCHEMA fsp_notifications;
-- (indexes, constraints and checks travel with their tables; FKs to persons/tenants stay
-- valid across the schema boundary)

-- Same claiming discipline as V23's push: each channel gets its own claim cursor,
-- bounded attempt counter and delivery-evidence `*_via` column, mirroring the push
-- columns exactly (one pattern, learned once). A channel with no credentials is ABSENT:
-- rows are claimed with via = 'NONE' by the dispatcher, never stuck.

ALTER TABLE fsp_notifications.notifications
  ADD COLUMN email_sent_at TIMESTAMPTZ,
  ADD COLUMN email_attempts INT    NOT NULL DEFAULT 0, -- bounded retry: give up at 3 transient failures
  ADD COLUMN email_via      TEXT,                       -- SMTP | NONE (no channel/pref/address)
  ADD COLUMN sms_sent_at    TIMESTAMPTZ,
  ADD COLUMN sms_attempts   INT    NOT NULL DEFAULT 0,
  ADD COLUMN sms_via        TEXT;                       -- provider id (fsp.sms.provider) | NONE

CREATE INDEX ix_notifications_email ON fsp_notifications.notifications (created_at) WHERE email_sent_at IS NULL;
CREATE INDEX ix_notifications_sms   ON fsp_notifications.notifications (created_at) WHERE sms_sent_at IS NULL;

-- Per-person outbound-channel consent (opt-IN). Email/SMS write to a mailbox/MSISDN, so
-- unlike push (a device registered by its owner) they are dormant until the person turns
-- them on from their own profile. `dead` = definitive carrier/bounce verdict on the
-- profile contact (e.g. invalid mailbox); the person can re-enable via the profile widget.
CREATE TABLE fsp_notifications.notification_channel_prefs (
  person_id  UUID    NOT NULL REFERENCES persons(id), -- cross-schema FK to public.persons (intentional)
  channel    TEXT    NOT NULL CHECK (channel IN ('EMAIL','SMS')),
  enabled    BOOLEAN NOT NULL DEFAULT false,
  dead       BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (person_id, channel)
);
