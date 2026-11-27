-- User-management list (GET /v1/tenants/{id}/users): every query filters on tenant_id, usually
-- on status too, and pages ordered by username. Before this, persons had no tenant_id index, so
-- each page and the per-status counts scanned the whole table.
CREATE INDEX persons_tenant_status_username_idx ON persons (tenant_id, status, username);
