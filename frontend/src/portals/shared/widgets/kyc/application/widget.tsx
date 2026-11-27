/**
 * kyc.application — "My organization": this organization's KYC standing as the onboarding parent
 * recorded it (case, risk rating, next periodic review). Read-only; the parent decides.
 */
import { useQuery } from '@tanstack/react-query';
import { useWidgetRuntime } from '../../core/runtime';
import { Chip, KvPair, PageHeader, Panel, StatusBadge } from '../../../ui/atoms';
import { StatePanel } from '../../kit/StatePanel';
import { isForbidden, toProblem } from '../../kit/problem';
import { ApiError } from '../../../web-core/api';

interface OrganizationRow {
  tenantId: string;
  legalName: string | null;
  subType: string | null;
  status: string;
  verificationStatus: string;
  caseId: string | null;
  caseStatus: string | null;
  riskRating: string | null;
  nextReviewDate: string | null;
}

export function KycApplication() {
  const { port, context } = useWidgetRuntime();
  const orgId = context.orgId ?? '';
  const org = useQuery({
    queryKey: ['kyc', 'self', orgId],
    queryFn: () => port.request<OrganizationRow>('kyc.application.self', { tenantId: orgId }),
    enabled: orgId !== '',
  });
  if (org.isPending) return <StatePanel kind="loading" title="Loading your organization" />;
  if (org.isError) {
    if (org.error instanceof ApiError && org.error.status === 503) {
      return <StatePanel kind="empty" title="KYC is not enabled for your organization" hint="Your onboarding parent enables the KYC plugin." />;
    }
    return isForbidden(org.error)
      ? <StatePanel kind="forbidden" title="Not allowed here" hint="Your organization’s KYC standing needs kyc.review." />
      : <StatePanel kind="error" title="Couldn’t load your KYC standing" hint={toProblem(org.error).message} onRetry={() => void org.refetch()} />;
  }
  const o = org.data;
  return (
    <Panel className="p-4">
      <PageHeader title="My organization" subtitle={o.legalName ?? ''} />
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
        <KvPair label="Type">{o.subType ? <Chip tone="accent">{o.subType}</Chip> : '—'}</KvPair>
        <KvPair label="Status"><StatusBadge status={o.status} /></KvPair>
        <KvPair label="Verification"><StatusBadge status={o.verificationStatus} /></KvPair>
        <KvPair label="KYC case">{o.caseStatus ? <StatusBadge status={o.caseStatus} /> : 'No case on file'}</KvPair>
        <KvPair label="Risk rating">{o.riskRating ?? '—'}</KvPair>
        <KvPair label="Next review" mono>{o.nextReviewDate ?? '—'}</KvPair>
      </div>
    </Panel>
  );
}
