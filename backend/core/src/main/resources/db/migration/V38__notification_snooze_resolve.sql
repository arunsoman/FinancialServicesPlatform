-- V38: actionable inbox. A notification is "pending" until it is acted upon:
--   resolved_at   — the thing it points at no longer needs anyone (approval decided by ANY
--                   checker), or the person dismissed an informational notice.
--   snoozed_until — the person parked it; it is hidden from the pending inbox until then and
--                   reappears on its own (read-time comparison, no scheduler).
ALTER TABLE fsp_notifications.notifications
    ADD COLUMN snoozed_until TIMESTAMPTZ,
    ADD COLUMN resolved_at   TIMESTAMPTZ;

-- the pending inbox / badge read: one person's unresolved rows, newest first
CREATE INDEX ix_notifications_pending
    ON fsp_notifications.notifications (person_id, created_at DESC)
    WHERE resolved_at IS NULL;

-- the approval-decided sweep: every checker's pointer at one approval
CREATE INDEX ix_notifications_resource
    ON fsp_notifications.notifications (resource_type, resource_id)
    WHERE resolved_at IS NULL;

-- backfill: pointers at approvals that were already decided are no longer pending
UPDATE fsp_notifications.notifications n
   SET resolved_at = COALESCE(a.decided_at, now())
  FROM approval_requests a
 WHERE n.resource_type = 'ApprovalRequest'
   AND n.resource_id = a.id
   AND a.status <> 'PENDING_CHECKER'
   AND n.resolved_at IS NULL;
