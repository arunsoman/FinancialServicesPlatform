-- V26: inventory + digital-value permission catalog (plans/inventory-plugin-plan.md D5e).
-- Versioned after the seed folders' V17 (system) / V18 (demo) / V22 (demo) because
-- Flyway numbers ALL locations in one sequence. Applies to EVERY environment, touches no demo data.
--
-- Plugin: `inventory` (backend/plugins/inventory, per docs/inventory-and-services-script-steps.md
-- GAP-INV-1 and GAP-VAS-1 subset). Maker/checker split mirrors kyc.*: *.manage / *.upload / *.dispense
-- are maker-side, *.approve is checker-side; je.post.immediate is the separately-grantable
-- single-actor posting key (JournalService.postIntent, plans/inventory-plugin-plan.md D3).

INSERT INTO permissions (key, title, risk, component) VALUES
  ('inventory.view',            'View SKUs and stock batches',                'READ',       'inventory'),
  ('inventory.sku.manage',      'Create and price SKUs (maker)',              'WRITE',      'inventory'),
  ('inventory.pricing.approve', 'Approve SKU pricing (checker)',              'FOUR_EYES',  'inventory'),
  ('inventory.stock.upload',    'Upload stock receipts (maker)',              'WRITE',      'inventory'),
  ('inventory.stock.approve',   'Approve stock receipts (checker)',           'FOUR_EYES',  'inventory'),
  ('digital.view',              'View digital products, vouchers and float',  'READ',       'inventory'),
  ('digital.product.manage',    'Manage digital products',                    'WRITE',      'inventory'),
  ('digital.voucher.upload',    'Upload voucher PIN pools (maker)',           'WRITE',      'inventory'),
  ('digital.voucher.authorize', 'Authorize voucher pools for sale (checker)', 'FOUR_EYES',  'inventory'),
  ('digital.float.deposit',     'Fund and approve merchant digital float',    'WRITE',      'inventory'),
  ('digital.dispense',          'Dispense vouchers and post retail money',    'WRITE',      'inventory'),
  ('je.post.immediate',         'Post journal intents without four-eyes',     'WRITE',      'journal')
ON CONFLICT (key) DO NOTHING;

-- the platform administrator role holds every catalog key (system seed V2 rule)
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, 'je.post.immediate' FROM roles r
WHERE r.key = 'PLATFORM_ADMIN' AND r.scope = 'PLATFORM'
ON CONFLICT DO NOTHING;

-- Maker-side keys: every organization's admin can run its own inventory + digital value.
-- NOTE: je.post.immediate is deliberately granted to TENANT_ADMIN here (an org admin is the
-- retail poster of record); orgs that require four-eyes on retail money must revoke this key
-- per-role — dispense flows then route through the four-eyes float/voucher pipelines instead.
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, k.key
FROM roles r
CROSS JOIN (VALUES ('inventory.view'), ('inventory.sku.manage'), ('inventory.stock.upload'),
                   ('digital.view'), ('digital.product.manage'), ('digital.voucher.upload'),
                   ('digital.float.deposit'), ('digital.dispense'), ('je.post.immediate')) AS k(key)
WHERE r.key = 'TENANT_ADMIN'
  AND r.scope IN ('TENANT', 'SUBTREE')
ON CONFLICT DO NOTHING;

-- Checker-side keys: every org's checker approves what its admin makes.
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, k.key
FROM roles r
CROSS JOIN (VALUES ('inventory.view'), ('inventory.pricing.approve'), ('inventory.stock.approve'),
                   ('digital.view'), ('digital.voucher.authorize')) AS k(key)
WHERE r.key = 'TENANT_CHECKER'
  AND r.scope IN ('TENANT', 'SUBTREE')
ON CONFLICT DO NOTHING;

-- (AGENTS §2f: index/query tuning for inventory collections lives in the plugin's own DB —
-- see backend/plugins/inventory .../db/migration/V1__inventory_schema.sql. Core owns no
-- inventory tables, so no core-side indexes are added here.)
