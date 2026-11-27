/** kyc.application data contributions — this organization's own KYC standing (KYC plugin). */
import type { DataSources } from '../../core/sources';
import type { FixtureSet, FixtureRequest } from '../../core/ports';
import { forbidUnless } from '../../kit/forbid';

export const sources: DataSources = {
  'kyc.application.self': { '*': { method: 'GET', path: '/api/plugins/kyc/organizations/{tenantId}' } },
};

const ORG = '00000000-0000-0000-0000-0000000000a2';

/** One organization record as the KYC plugin answers it (core KYC was retired in V13). */
export const fixtures: FixtureSet = {
  'kyc.application.self': {
    default: (req: FixtureRequest) => {
      forbidUnless(req.permissions, 'kyc.review');
      return {
        tenantId: ORG,
        legalName: 'Etosha Trading Co (Pty) Ltd',
        subType: 'MERCHANT',
        status: 'ACTIVE',
        verificationStatus: 'CASE',
        caseId: '00000000-0000-0000-0000-0000000000b4',
        caseStatus: 'PENDING_CHECKER',
        riskRating: 'MEDIUM',
        nextReviewDate: '2027-06-30',
      };
    },
    empty: null,
    error: null,
  },
};

export const KYC_ORG_ID = ORG;
