-- V19: roles governance + provisioning-template backfill (docs/onboarding-script-steps.md
-- GAP-1, GAP-5, GAP-9). Versioned after the seed folders' V17 (system) / V18 (demo) because
-- Flyway numbers ALL locations in one sequence.
--
-- Everything here applies to EVERY environment and touches no demo data: it fixes rows the
-- platform itself provisioned before the templates in com.fsp.core.rbac.RoleTemplates
-- existed. No migration ever grants a key outside those templates.

-- 1. Read-only twin of role.manage: lets an org's people SEE its roles without editing them.
INSERT INTO permissions (key, title, risk, component) VALUES
  ('role.view', 'View roles and their permissions', 'READ', 'rbac')
ON CONFLICT (key) DO NOTHING;

-- the platform administrator role holds every catalog key (system seed V2 rule)
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, 'role.view' FROM roles r
WHERE r.key = 'PLATFORM_ADMIN' AND r.scope = 'PLATFORM'
ON CONFLICT DO NOTHING;

-- 2. GAP-5: every PSP under the platform root is a supply-chain MANUFACTURER
--    (TenantService.PSP_SUB_TYPE); legacy PSP rows were created without a sub_type.
UPDATE tenants SET sub_type = 'MANUFACTURER'
WHERE type = 'PSP' AND sub_type IS NULL;

-- 3. GAP-1: bring auto-provisioned roles up to RoleTemplates (additive only — nothing revoked).
--    PSP / non-leaf org admins: run their org + onboard children.
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, k.key
FROM roles r
JOIN tenants t ON t.id = r.tenant_id
CROSS JOIN (VALUES ('kyc.review'), ('user.manage'), ('role.view'), ('role.manage'),
                   ('org.onboard_child'), ('org.create'), ('screening.run')) AS k(key)
WHERE r.key = 'TENANT_ADMIN'
  AND t.type IN ('PSP', 'TENANT')
  AND COALESCE(t.sub_type, '') <> 'MERCHANT'
ON CONFLICT DO NOTHING;

--    Leaf (MERCHANT) admins: run their own org only.
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, k.key
FROM roles r
JOIN tenants t ON t.id = r.tenant_id
CROSS JOIN (VALUES ('kyc.review'), ('user.manage'), ('role.view'), ('role.manage')) AS k(key)
WHERE r.key = 'TENANT_ADMIN'
  AND t.type = 'TENANT'
  AND t.sub_type = 'MERCHANT'
ON CONFLICT DO NOTHING;

--    Checkers: decide every four-eyes request their organization raises.
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, k.key
FROM roles r
JOIN tenants t ON t.id = r.tenant_id
CROSS JOIN (VALUES ('kyc.review'), ('kyc.approve'), ('kyc.reject'),
                   ('user.manage'), ('role.view'), ('role.manage')) AS k(key)
WHERE r.key = 'TENANT_CHECKER'
  AND t.type IN ('PSP', 'TENANT')
ON CONFLICT DO NOTHING;

-- 4. GAP-9: the checker an organization was BORN with is its designated checker
--    (PersonProvisioning.designateChecker). "Born with" = holds TENANT_CHECKER and was NOT
--    created through the four-eyes user-admin flow (no USER_CREATE request targets it) —
--    so a user an admin deliberately created with checker=false is never flipped.
UPDATE persons p SET is_checker = TRUE
WHERE p.is_checker = FALSE
  AND p.status = 'ACTIVE'
  AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
              WHERE ur.person_id = p.id AND r.key = 'TENANT_CHECKER' AND r.tenant_id = p.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM approval_requests a
                  WHERE a.target_resource_id = p.id AND a.operation_type = 'USER_CREATE');
