-- V2: customer KYC + activation tracking (core V36)
-- A case is now of a KIND: ORGANIZATION (the existing org onboarding) or CUSTOMER (an
-- individual onboarded by staff). Customer identity fields live HERE (the plugin owns the
-- KYC record); core only holds the person (username = primary contact, status, verified-at).

ALTER TABLE kyc_cases
    ADD COLUMN case_kind VARCHAR(16) NOT NULL DEFAULT 'ORGANIZATION'
        CONSTRAINT kyc_cases_kind CHECK (case_kind IN ('ORGANIZATION', 'CUSTOMER')),
    ADD COLUMN subject_person_id   UUID,          -- CUSTOMER: the core person
    ADD COLUMN first_name          TEXT,
    ADD COLUMN last_name           TEXT,
    ADD COLUMN gender              VARCHAR(16),
    ADD COLUMN date_of_birth       DATE,
    ADD COLUMN phone               VARCHAR(20),   -- E.164
    ADD COLUMN email               TEXT,
    ADD COLUMN primary_contact     VARCHAR(5)
        CONSTRAINT kyc_cases_primary_contact CHECK (primary_contact IN ('EMAIL', 'PHONE')),
    ADD COLUMN contact_verified_at TIMESTAMPTZ,   -- cache of core's persons.contact_verified_at
    ADD COLUMN activation_sent_at  TIMESTAMPTZ,
    -- ORGANIZATION: the org-born persons who activate by link/code (from core)
    ADD COLUMN admin_person_id     UUID,
    ADD COLUMN checker_person_id_subject UUID;

-- organization-only columns are not meaningful for an individual
ALTER TABLE kyc_cases ALTER COLUMN sub_type DROP NOT NULL;
ALTER TABLE kyc_cases ALTER COLUMN entity_type DROP NOT NULL;
ALTER TABLE kyc_cases ALTER COLUMN registration_number DROP NOT NULL;

ALTER TABLE kyc_cases ADD CONSTRAINT kyc_cases_org_fields CHECK (
    case_kind <> 'ORGANIZATION'
    OR (sub_type IS NOT NULL AND entity_type IS NOT NULL AND registration_number IS NOT NULL));
ALTER TABLE kyc_cases ADD CONSTRAINT kyc_cases_customer_fields CHECK (
    case_kind <> 'CUSTOMER'
    OR (first_name IS NOT NULL AND last_name IS NOT NULL AND gender IS NOT NULL
        AND date_of_birth IS NOT NULL AND primary_contact IS NOT NULL));

CREATE INDEX idx_kyc_cases_owner_kind ON kyc_cases(owner_tenant_id, case_kind, submitted_at DESC);
-- one open/approved customer case per contact inside an installation
CREATE UNIQUE INDEX ux_kyc_cases_customer_email ON kyc_cases(owner_tenant_id, lower(email))
    WHERE case_kind = 'CUSTOMER' AND email IS NOT NULL AND status <> 'REJECTED';
CREATE UNIQUE INDEX ux_kyc_cases_customer_phone ON kyc_cases(owner_tenant_id, phone)
    WHERE case_kind = 'CUSTOMER' AND phone IS NOT NULL AND status <> 'REJECTED';
