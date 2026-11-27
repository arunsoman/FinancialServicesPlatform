-- V27: demo-role grants for the inventory + digital-value keys (demo seed, client-demo only).
-- Complements V26 (catalog + template grants) for the demo seed's V3 role rows, which were
-- provisioned before those keys existed (V19-style additive backfill, nothing revoked).
--
-- Demo merchant (Namib Fresh) admin becomes the retail poster; the checker gets the
-- plugin's four-eyes keys. Demo PSP roles only get read access (its book is display-only).

INSERT INTO role_permissions (role_id, permission_key)
SELECT '00000000-0000-0000-0000-0000000002a1', key FROM permissions
WHERE key IN ('inventory.view','inventory.sku.manage','inventory.stock.upload',
              'digital.view','digital.product.manage','digital.voucher.upload',
              'digital.float.deposit','digital.dispense','je.post.immediate')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_key)
SELECT '00000000-0000-0000-0000-0000000002a2', key FROM permissions
WHERE key IN ('inventory.view','inventory.pricing.approve','inventory.stock.approve',
              'digital.view','digital.voucher.authorize')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_key)
SELECT '00000000-0000-0000-0000-0000000001a1', key FROM permissions
WHERE key IN ('inventory.view','digital.view')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_key)
SELECT '00000000-0000-0000-0000-0000000001a2', key FROM permissions
WHERE key IN ('inventory.view','digital.view')
ON CONFLICT DO NOTHING;
