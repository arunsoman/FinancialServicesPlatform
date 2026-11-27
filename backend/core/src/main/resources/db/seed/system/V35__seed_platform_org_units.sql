-- The platform root organization's starting branch + departments (system reference data, not
-- demo data: every environment's platform org needs at least one of each, because the Add Role
-- form requires a branch and a department whenever the organization has any). Managed later
-- through Role Management → Branch / Department; ids fixed so re-runs and tests can name them.
INSERT INTO branches (id, tenant_id, code, name) VALUES
  ('00000000-0000-0000-0000-000000000b01', '00000000-0000-0000-0000-000000000001', 'HO', 'Head Office');

INSERT INTO departments (id, tenant_id, code, name) VALUES
  ('00000000-0000-0000-0000-000000000d01', '00000000-0000-0000-0000-000000000001', 'OPS', 'Operations'),
  ('00000000-0000-0000-0000-000000000d02', '00000000-0000-0000-0000-000000000001', 'FIN', 'Finance'),
  ('00000000-0000-0000-0000-000000000d03', '00000000-0000-0000-0000-000000000001', 'CMP', 'Compliance'),
  ('00000000-0000-0000-0000-000000000d04', '00000000-0000-0000-0000-000000000001', 'IT',  'Information Technology');
