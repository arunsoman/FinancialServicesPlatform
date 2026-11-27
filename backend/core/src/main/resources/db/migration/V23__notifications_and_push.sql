-- V23 · F-12 — notifications & push subscriptions.
-- The durable record of what a checker was pointed at (an approval is pending), plus
-- per-device push registrations (Web Push today; APNs/FCM adapters when credentials are
-- provisioned). The push dispatcher scans `notifications.pushed_at IS NULL` — the table
-- IS the push queue (crash-safe, catch-up after downtime, no cursor state).

CREATE TABLE notifications (
  id            UUID PRIMARY KEY,
  person_id     UUID   NOT NULL REFERENCES persons(id),
  tenant_id     UUID   NOT NULL REFERENCES tenants(id),
  type          TEXT   NOT NULL,            -- APPROVAL_OPEN (| later: APPROVAL_RESOLVED, …)
  op_type       TEXT   NOT NULL,            -- canonical approval op (BANK_ACCOUNT_CREATE, USER_CREATE, …)
  title         TEXT   NOT NULL,            -- generic, PII-free (AGENTS §2c): "Awaiting approval"
  body          TEXT   NOT NULL,            -- generic pointer, never amounts/names/account data
  resource_type TEXT   NOT NULL,
  resource_id   UUID   NOT NULL,
  link          TEXT   NOT NULL,            -- in-app path (portal-relative), e.g. /approvals
  read_at       TIMESTAMPTZ,
  pushed_at     TIMESTAMPTZ,
  push_attempts INT    NOT NULL DEFAULT 0, -- bounded retry: dispatcher gives up after 3 non-definitive failures
  push_via      TEXT,                       -- WEBPUSH | APNS | FCM | NONE (no channel/credentials)
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A checker is notified ONCE per open resource; the row re-fires nothing.
CREATE UNIQUE INDEX ux_notifications_once
  ON notifications (person_id, resource_type, resource_id);
CREATE INDEX ix_notifications_person ON notifications (person_id, created_at DESC);
CREATE INDEX ix_notifications_push   ON notifications (created_at) WHERE pushed_at IS NULL;

CREATE TABLE push_subscriptions (
  id         UUID PRIMARY KEY,
  person_id  UUID   NOT NULL REFERENCES persons(id),
  tenant_id  UUID   NOT NULL REFERENCES tenants(id),
  platform   TEXT   NOT NULL CHECK (platform IN ('WEBPUSH','APNS','FCM')),
  endpoint   TEXT,                         -- WEBPUSH push-service URL
  token      TEXT,                         -- APNs / FCM device token
  p256dh     TEXT,                         -- WEBPUSH RFC 8291 client public key
  auth       TEXT,                         -- WEBPUSH auth secret
  user_agent TEXT,
  status     TEXT   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DEAD')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_push_at TIMESTAMPTZ,
  -- one identity per device: WEBPUSH by its push-service URL, APNs/FCM by token
  CONSTRAINT one_webpush CHECK ((platform = 'WEBPUSH') = (endpoint IS NOT NULL)),
  CONSTRAINT one_token   CHECK ((platform IN ('APNS','FCM')) = (token IS NOT NULL))
);

CREATE UNIQUE INDEX ux_push_sub_endpoint ON push_subscriptions (endpoint) WHERE platform = 'WEBPUSH' AND status = 'ACTIVE';
CREATE UNIQUE INDEX ux_push_sub_token    ON push_subscriptions (token)    WHERE platform IN ('APNS','FCM') AND status = 'ACTIVE';
CREATE INDEX ix_push_sub_person ON push_subscriptions (person_id, status);

-- `notification.view` (READ): the inbox + unread count + VAPID public key + subscribing
-- one's own devices. Self-service surface — no separate WRITE key; WRITE ops are still
-- idempotent-keyed and permission-gated by this key.
INSERT INTO permissions (key, title, risk, component) VALUES
  ('notification.view', 'View notifications and manage my device subscriptions', 'READ', 'notify')
ON CONFLICT (key) DO NOTHING;

-- Deliberate grants: both planes need the inbox (checkers are the primary recipients;
-- admins read it too). Born-role templates pick it up via RoleTemplates.
INSERT INTO role_permissions (role_id, permission_key)
SELECT id, 'notification.view' FROM roles
WHERE id IN (
  '00000000-0000-0000-0000-000000000101', -- Platform Administrator
  '00000000-0000-0000-0000-0000000001a1', -- PSP Administrator
  '00000000-0000-0000-0000-0000000001a2', -- TPTS Operations
  '00000000-0000-0000-0000-0000000002a1', -- Organization Administrator
  '00000000-0000-0000-0000-0000000002a2'  -- Organization Checker
)
ON CONFLICT DO NOTHING;

-- Mirror the born-role templates (V19 rule): every TENANT_ADMIN / TENANT_CHECKER role that
-- was born before this migration holds the inbox too — checkers are the primary recipients.
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, 'notification.view'
FROM roles r JOIN tenants t ON t.id = r.tenant_id
WHERE r.key IN ('TENANT_ADMIN', 'TENANT_CHECKER')
  AND t.type IN ('PSP', 'TENANT')
ON CONFLICT DO NOTHING;
