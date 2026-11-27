-- V30 — Auth-plane dispatch (docs/architecture/12-notifications-and-push.md §12.9)
--
-- Authentication-adjacent messages (password reset, MFA login/ enrolment OTP) ride the SAME
-- outbound transports as F-12 notifications (SMTP email + SMS gateway), but never the
-- notification inbox: they are delivered pre-auth or pre-inbox, must not be opt-out, and
-- must not be deduped per resource. Split of ownership:
--
--   IdP plane (public schema, per the V6 idp policy convention):
--     password_resets, mfa_challenges, user_mfa   — auth logic, secrets (hashed), state machines
--   Notify plane (fsp_notifications schema):
--     auth_dispatches                             — delivery ONLY, claimed by core's
--                                                   AuthDispatchDispatcher via the same
--                                                   transports as V29.
--
-- Secret hygiene: secret-bearing fields are ALWAYS hashed (token_hash / code_hash, SHA-256).
-- auth_dispatches.text carries the rendered message (may embed the one-time code/link) —
-- transient evidence, NULLed on claim. Contact is NEVER stored; the dispatcher resolves
-- email/phone from persons at send time.

-- ---------- notify plane: the auth dispatch queue ----------

CREATE TABLE IF NOT EXISTS fsp_notifications.auth_dispatches (
    id             UUID PRIMARY KEY,
    purpose        VARCHAR(16)  NOT NULL
                   CONSTRAINT auth_dispatch_purpose
                   CHECK (purpose IN ('PASSWORD_RESET', 'MFA_LOGIN', 'MFA_ENROLMENT')),
    person_id      UUID         NOT NULL
                   CONSTRAINT fk_auth_dispatch_person REFERENCES public.persons(id),
    channel        VARCHAR(4)   NOT NULL
                   CONSTRAINT auth_dispatch_channel CHECK (channel IN ('EMAIL', 'SMS')),
    subject        VARCHAR(200) NOT NULL,
    text           TEXT         NOT NULL,
    correlation_id VARCHAR(64),
    attempts       INT          NOT NULL DEFAULT 0,
    via            VARCHAR(12),
    sent_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- pending cursor: same shape as F-12 channel cursors (partial index on the unsent set)
CREATE INDEX IF NOT EXISTS ix_auth_dispatch_pending
    ON fsp_notifications.auth_dispatches (created_at)
    WHERE sent_at IS NULL;

-- ---------- IdP plane: password reset tokens (hashed, single-use, 15 min) ----------

CREATE TABLE IF NOT EXISTS public.password_resets (
    id          UUID PRIMARY KEY,
    person_id   UUID         NOT NULL
                CONSTRAINT fk_password_reset_person REFERENCES public.persons(id),
    token_hash  CHAR(64)     NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- one live reset per person; a new request supersedes (consumes) older pending rows
CREATE UNIQUE INDEX IF NOT EXISTS uq_password_reset_pending
    ON public.password_resets (person_id)
    WHERE consumed_at IS NULL;

-- ---------- IdP plane: MFA second factors ----------

-- one row per MFA-enrolled person (the row IS the enrollment; no row = MFA off)
CREATE TABLE IF NOT EXISTS public.user_mfa (
    person_id  UUID PRIMARY KEY
               CONSTRAINT fk_user_mfa_person REFERENCES public.persons(id),
    channel    VARCHAR(4)  NOT NULL
               CONSTRAINT user_mfa_channel CHECK (channel IN ('EMAIL', 'SMS')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- per-verification challenges (login second step / enrolment confirm); code stored hashed
CREATE TABLE IF NOT EXISTS public.mfa_challenges (
    id          UUID PRIMARY KEY,
    person_id   UUID         NOT NULL
                CONSTRAINT fk_mfa_challenge_person REFERENCES public.persons(id),
    purpose     VARCHAR(12)  NOT NULL
                CONSTRAINT mfa_challenge_purpose CHECK (purpose IN ('LOGIN', 'ENROLMENT')),
    channel     VARCHAR(4)   NOT NULL
                CONSTRAINT mfa_challenge_channel CHECK (channel IN ('EMAIL', 'SMS')),
    code_hash   CHAR(64)     NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_mfa_challenge_pending
    ON public.mfa_challenges (person_id, purpose)
    WHERE consumed_at IS NULL;
