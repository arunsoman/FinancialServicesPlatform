/** onboard.psp data contributions — the platform creates PSP (manufacturer) organizations. */
import type { DataSources } from '../../core/sources';
import type { FixtureSet, FixtureRequest } from '../../core/ports';
import type { PspCreated, PspRow } from './model';
import { forbidUnless } from '../../kit/forbid';

export const sources: DataSources = {
  'onboard.psp.list': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/children' } },
};

export const commands: DataSources = {
  'onboard.psp.create': { '*': { method: 'POST', path: '/api/tenants' } },
};

const psps: PspRow[] = [
  {
    id: '00000000-0000-0000-0000-0000000000b1',
    legalName: 'Swipe Payment Services (Pty) Ltd',
    type: 'TENANT',
    subType: 'MANUFACTURER',
    status: 'ACTIVE',
    verificationStatus: 'APPROVED',
    createdAt: '2026-09-02T09:15:00Z',
  },
  {
    id: '00000000-0000-0000-0000-0000000000b2',
    legalName: 'Namib Fresh Produce (Pty) Ltd',
    type: 'TENANT',
    subType: 'MANUFACTURER',
    status: 'ACTIVE',
    verificationStatus: 'CASE',
    createdAt: '2026-10-30T13:40:00Z',
  },
];

const ORG = '00000000-0000-0000-0000-0000000000a1';

export const fixtures: FixtureSet = {
  'onboard.psp.list': {
    default: (req: FixtureRequest): PspRow[] => {
      forbidUnless(req.permissions, 'org.create');
      return psps;
    },
    empty: [],
    error: null,
  },
  'onboard.psp.create': {
    // POST /v1/tenants with a tenant body → tenant + admin + checker (UsernamePolicy names).
    default: (req: FixtureRequest): PspCreated => {
      forbidUnless(req.permissions, 'org.create');
      const v = req.body as { legalName?: string; adminUsername?: string; checkerUsername?: string };
      const slug = (v.legalName ?? 'new org').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40);
      return {
        tenantId: '00000000-0000-0000-0000-0000000000b3',
        adminUsername: v.adminUsername ?? `${slug}-admin`,
        checkerUsername: v.checkerUsername ?? `${slug}-check`,
        note: 'Organization created with admin and checker persons; IdP credentials follow the bootstrap policy.',
      };
    },
    error: null,
  },
};

/** Re-exported so tests pin the parent used by both the list params and the create flow. */
export const PSP_ROOT_ID = ORG;
