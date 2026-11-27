-- V3: a customer carries an address, like an organization does.
-- Same columns as the organization's registered / operating address:
--   registered_*  = the customer's RESIDENTIAL address (required)
--   operational_* = the POSTAL address when it differs (operational_address_same = false)
-- Customer cases arrived in V2 (same release) — no row predates this rule, so it is validated.
ALTER TABLE kyc_cases ADD CONSTRAINT kyc_cases_customer_address CHECK (
    case_kind <> 'CUSTOMER'
    OR (registered_address IS NOT NULL AND registered_city IS NOT NULL AND registered_region IS NOT NULL
        AND (operational_address_same
             OR (operational_address IS NOT NULL AND operational_city IS NOT NULL AND operational_region IS NOT NULL)))
);
