-- V36 — Customer KYC onboarding + contact-verified activation
--
-- A customer is a person (persons.is_customer) onboarded by staff through the KYC plugin
-- (maker). The customer is born PENDING with NO usable password: the IdP sends a one-time
-- activation to the PRIMARY contact (email link, or an SMS one-time code), the customer sets
-- their own password at the IdP (hosted page, the secret never reaches staff, BFF or SPA) and
-- that proves control of the contact (contact_verified_at). Only then may a checker (≠ maker,
-- four-eyes at person_id) activate — core refuses ACTIVE without contact_verified_at.

ALTER TABLE public.persons
    ADD COLUMN IF NOT EXISTS is_customer         BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS primary_contact     VARCHAR(5)
        CONSTRAINT persons_primary_contact CHECK (primary_contact IN ('EMAIL', 'PHONE')),
    ADD COLUMN IF NOT EXISTS contact_verified_at TIMESTAMPTZ;

-- one live customer per email / per phone across the platform (case-insensitive email);
-- archived customers free their contact for re-onboarding
CREATE UNIQUE INDEX IF NOT EXISTS uq_persons_customer_email
    ON public.persons (lower(email))
    WHERE is_customer AND email IS NOT NULL AND status <> 'ARCHIVED';
CREATE UNIQUE INDEX IF NOT EXISTS uq_persons_customer_phone
    ON public.persons (phone)
    WHERE is_customer AND phone IS NOT NULL AND status <> 'ARCHIVED';

-- staff user lists exclude customers; keep that filter index-backed
CREATE INDEX IF NOT EXISTS ix_persons_tenant_staff
    ON public.persons (tenant_id, status)
    WHERE NOT is_customer;

-- IdP plane: an activation is a password_resets row with purpose ACTIVATION. EMAIL carries a
-- 256-bit link token; SMS carries a 6-digit code (hash bound to the person, attempts capped).
ALTER TABLE public.password_resets
    ADD COLUMN IF NOT EXISTS purpose VARCHAR(10) NOT NULL DEFAULT 'RESET'
        CONSTRAINT password_reset_purpose CHECK (purpose IN ('RESET', 'ACTIVATION')),
    ADD COLUMN IF NOT EXISTS channel VARCHAR(5)  NOT NULL DEFAULT 'EMAIL'
        CONSTRAINT password_reset_channel CHECK (channel IN ('EMAIL', 'SMS'));

-- notify plane: the activation message rides the same auth dispatch queue
ALTER TABLE fsp_notifications.auth_dispatches DROP CONSTRAINT IF EXISTS auth_dispatch_purpose;
ALTER TABLE fsp_notifications.auth_dispatches ADD CONSTRAINT auth_dispatch_purpose
    CHECK (purpose IN ('PASSWORD_RESET', 'MFA_LOGIN', 'MFA_ENROLMENT', 'ACTIVATION'));
