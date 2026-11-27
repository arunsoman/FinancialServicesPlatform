/**
 * kyc.orgs — the organization tree with KYC status (plans §6.3).
 * Requires `kyc.review`. Org portals see their own node + subtree
 * (server-enforced reachability); the admin lane sees what it can reach.
 * The four-operation mapping: View only (pure read widget).
 */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
const kycOrgsWidget = defineWidget({
  id: 'kyc.orgs',
  version: '1.1.0',
  label: 'Organizations',
  description: 'The organization tree around you, each node with its KYC status.',
  category: 'Onboarding',
  contexts: ['ANY'],
  requires: ['kyc.review'],
  sources: ['kyc.orgs.self', 'kyc.orgs.tree'],
  actions: [],
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6), lg: span.lg(4) },
  hideOn: [],
  render: KycOrgs,
});
import { KycOrgs } from './widget';

export default kycOrgsWidget;
