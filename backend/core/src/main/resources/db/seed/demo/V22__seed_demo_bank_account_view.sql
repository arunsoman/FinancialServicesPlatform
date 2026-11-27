-- V19 (demo seed only) · F-10 — grant bank.account.view to the demo plane roles.
-- Pairs with V21__bank_accounts.sql (which creates the permission). Kept as a NEW seed
-- migration, not an edit of V3, because applied migrations are immutable (AGENTS §2.5).
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, 'bank.account.view'
FROM roles r
WHERE r.id IN (
  '00000000-0000-0000-0000-0000000001a1', -- PSP Administrator
  '00000000-0000-0000-0000-0000000001a2', -- TPTS Operations
  '00000000-0000-0000-0000-0000000002a1', -- Tenant Admin (organization)
  '00000000-0000-0000-0000-0000000002a2'  -- Tenant Checker (organization)
)
ON CONFLICT DO NOTHING;
