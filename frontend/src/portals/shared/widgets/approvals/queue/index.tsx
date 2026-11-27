/**
 * approvals.queue — four-eyes inbox (GAP-3). Visible to anyone holding a checker permission;
 * whether THIS person may decide a row is the server's answer (canDecide/blockedReason).
 */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { ApprovalsQueue } from './widget';

const approvalsQueueWidget = defineWidget({
  id: 'approvals.queue',
  version: '1.0.0',
  label: 'Approvals',
  description: 'Every four-eyes request raised in the organization: review the proposal, approve or reject with a reason.',
  category: 'Operations',
  contexts: ['ANY'],
  requires: ['kyc.approve', 'kyc.reject', 'je.approve', 'user.manage', 'role.manage', 'plugin.manage', 'ui.layout.manage'],
  sources: ['approvals.queue.list'],
  commands: ['approvals.queue.approve', 'approvals.queue.reject'],
  actions: [
    { id: 'approval.approve', label: 'Approve', cell: 'user.manage', command: 'approvals.queue.approve', kind: 'primary' },
    { id: 'approval.reject', label: 'Reject', cell: 'user.manage', command: 'approvals.queue.reject', kind: 'danger' },
  ],
  commandOperations: { 'approvals.queue.approve': 'approve', 'approvals.queue.reject': 'approve' },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: ApprovalsQueue,
});

export default approvalsQueueWidget;
