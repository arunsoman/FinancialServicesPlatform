-- V20: plugin-consent keys for non-leaf organizations (PSP / distributor chains).
--
-- Business rule (marketplace consent model): the platform curates and publishes plugins
-- (plugin.register stays platform-only), but the ORDER whose perimeter a plugin's machine
-- client will operate in (AGENTS §6.5 reachability) holds the consent decision. Concretely:
--   * the tenant's checker decides the PLUGIN_INSTALL four-eyes request (decide key =
--     plugin.manage, ApprovalService), and
--   * that key must sit DIRECTLY at the tenant's roles for the act-as ceiling cap to let a
--     platform operator step in and run the same decision on the tenant's behalf
--     (PermissionService.effectiveKeysForActing: holds ∩ ceiling = roles directly at target).
-- Leaves (MERCHANT sub_type) get nothing — plugins install on supply-chain orgs in Phase-0.
-- Mirror: RoleTemplates.checkerKeys (non-leaf) keeps NEW organizations in step.

INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, 'plugin.manage'
FROM roles r
JOIN tenants t ON t.id = r.tenant_id
WHERE r.key = 'TENANT_CHECKER'
  AND t.type IN ('PSP', 'TENANT')
  AND COALESCE(t.sub_type, '') <> 'MERCHANT'
ON CONFLICT DO NOTHING;
