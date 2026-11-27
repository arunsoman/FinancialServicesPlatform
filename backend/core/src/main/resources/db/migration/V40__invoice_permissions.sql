-- V40: invoicing permission catalog (billing.invoices widget — stage 1 needs the keys to exist so
-- core's widget registry accepts the widget; the invoicing API itself lands in stage 2).
-- Applies to EVERY environment, touches no demo data.
--
-- Seller side: invoice.create (draft + issue), invoice.collect (confirm a bank transfer arrived),
-- invoice.void (discard a draft; voiding an ISSUED invoice opens a four-eyes approval).
-- Buyer side: invoice.pay. Both sides read with invoice.view.

INSERT INTO permissions (key, title, risk, component) VALUES
  ('invoice.view',    'View sales and purchase invoices',                  'READ',      'billing'),
  ('invoice.create',  'Create and issue invoices',                         'WRITE',     'billing'),
  ('invoice.pay',     'Pay received invoices (wallet, transfer, card)',    'WRITE',     'billing'),
  ('invoice.collect', 'Confirm money received for an invoice',             'WRITE',     'billing'),
  ('invoice.void',    'Void invoices (issued: four-eyes)',                 'FOUR_EYES', 'billing')
ON CONFLICT (key) DO NOTHING;

-- the platform administrator role holds every catalog key (system seed V2 rule)
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, k.key
FROM roles r
CROSS JOIN (VALUES ('invoice.view'), ('invoice.create'), ('invoice.pay'), ('invoice.collect'), ('invoice.void')) AS k(key)
WHERE r.key = 'PLATFORM_ADMIN' AND r.scope = 'PLATFORM'
ON CONFLICT DO NOTHING;

-- every organization's admin bills its buyers and pays its suppliers
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, k.key
FROM roles r
CROSS JOIN (VALUES ('invoice.view'), ('invoice.create'), ('invoice.pay'), ('invoice.collect'), ('invoice.void')) AS k(key)
WHERE r.key = 'TENANT_ADMIN'
  AND r.scope IN ('TENANT', 'SUBTREE')
ON CONFLICT DO NOTHING;

-- every organization's checker sees invoices (and decides void approvals through the approvals queue)
INSERT INTO role_permissions (role_id, permission_key)
SELECT r.id, 'invoice.view'
FROM roles r
WHERE r.key = 'TENANT_CHECKER'
  AND r.scope IN ('TENANT', 'SUBTREE')
ON CONFLICT DO NOTHING;
