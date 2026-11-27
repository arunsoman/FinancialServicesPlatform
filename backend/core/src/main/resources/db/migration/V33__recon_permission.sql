-- Stage 6 (recon-worker): read permission guarding recon pages/panels (docs/architecture/13 §8).
-- Append-only catalog: new keys + grants to the platform role template (seed-time SELECT
-- cannot cover post-V2 keys).
INSERT INTO permissions (key, title, risk, component) VALUES
  ('recon.view', 'View recon invariant violations and pages', 'READ', 'recon');

INSERT INTO role_permissions (role_id, permission_key)
SELECT '00000000-0000-0000-0000-000000000101', 'recon.view'
WHERE NOT EXISTS (
  SELECT 1 FROM role_permissions
  WHERE role_id = '00000000-0000-0000-0000-000000000101' AND permission_key = 'recon.view');
