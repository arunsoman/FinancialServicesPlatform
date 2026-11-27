-- V28: one pending approval per (operation_type, target_resource_id).
-- Four-eyes backstop for submit races (e.g. concurrent UI_LAYOUT_PUBLISH submits for the
-- same portal draft): the JVM KeyLocks serialize the check+open, this index makes the
-- invariant hold even across nodes. Resolved rows (APPROVED/REJECTED) are unaffected —
-- a target may carry many resolved approvals (layout draft rows are reused across publishes).
CREATE UNIQUE INDEX IF NOT EXISTS ux_approvals_pending_target
  ON approval_requests (operation_type, target_resource_id)
  WHERE status = 'PENDING_CHECKER';
