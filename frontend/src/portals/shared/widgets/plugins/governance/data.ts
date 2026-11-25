/** plugins.governance data contributions (core PluginGovernanceController + registry/installation). */
import type { DataSources } from '../../core/sources';
import type { FixtureSet, FixtureRequest } from '../../core/ports';
import type { InstallationRow, OrgRow, PluginTypeRow } from './model';
import { forbidUnless } from '../../kit/forbid';

export const sources: DataSources = {
  'plugins.governance.types': { '*': { method: 'GET', path: '/api/platform/plugins' } },
  'plugins.governance.orgs': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/children' } },
  'plugins.governance.installations': { '*': { method: 'GET', path: '/api/tenants/{orgId}/plugins' } },
};

export const commands: DataSources = {
  'plugins.governance.register': { '*': { method: 'POST', path: '/api/platform/plugins' } },
  'plugins.governance.install': { '*': { method: 'POST', path: '/api/tenants/{orgId}/plugins' } },
};

const types: PluginTypeRow[] = [
  {
    id: '00000000-0000-0000-0000-0000000000c2',
    code: 'kyconboarding',
    version: '1.0.0',
    name: 'KYC onboarding',
    baseUrl: 'http://localhost:8083',
    status: 'REGISTERED',
    manifestHash: 'e5c1d3f0a7b24c96',
    operationCount: 8,
    permissions: ['kyc.review'],
    registeredAt: '2026-10-12T10:00:00Z',
    approvedAt: '2026-10-12T14:30:00Z',
    pendingApprovalId: null,
  },
  {
    id: '00000000-0000-0000-0000-0000000000c3',
    code: 'ledgerreports',
    version: '0.3.1',
    name: 'Ledger reports',
    baseUrl: 'http://localhost:8084',
    status: 'PENDING_APPROVAL',
    manifestHash: '9ab4f7d2c0e65f11',
    operationCount: 4,
    permissions: ['je.view'],
    registeredAt: '2026-11-16T08:20:00Z',
    approvedAt: null,
    pendingApprovalId: '00000000-0000-0000-0000-0000000000c4',
  },
];

const orgs: OrgRow[] = [
  { id: '00000000-0000-0000-0000-0000000000a1', legalName: 'FSP Platform Root', type: 'PLATFORM', subType: null, status: 'ACTIVE' },
  { id: '00000000-0000-0000-0000-0000000000b1', legalName: 'Swipe Payment Services (Pty) Ltd', type: 'TENANT', subType: 'MANUFACTURER', status: 'ACTIVE' },
  { id: '00000000-0000-0000-0000-0000000000b2', legalName: 'Namib Fresh Produce (Pty) Ltd', type: 'TENANT', subType: 'MANUFACTURER', status: 'ACTIVE' },
];

const installs: InstallationRow[] = [
  {
    id: '00000000-0000-0000-0000-0000000000c5',
    tenantId: '00000000-0000-0000-0000-0000000000b1',
    pluginCode: 'kyconboarding',
    pluginVersion: '1.0.0',
    status: 'ACTIVE',
    inheritanceOrigin: null,
    gates: [
      { gate: 'CORE_REACHABLE', status: 'ACTIVE', updatedAt: '2026-10-13T07:05:00Z' },
      { gate: 'IDP_POLICY', status: 'ACTIVE', updatedAt: '2026-10-13T07:05:00Z' },
    ],
    ready: true,
    createdAt: '2026-10-13T07:05:00Z',
    updatedAt: '2026-11-01T09:00:00Z',
    pendingApprovalId: null,
  },
  {
    id: '00000000-0000-0000-0000-0000000000c6',
    tenantId: '00000000-0000-0000-0000-0000000000b2',
    pluginCode: 'ledgerreports',
    pluginVersion: '0.3.1',
    status: 'PENDING_APPROVAL',
    inheritanceOrigin: '00000000-0000-0000-0000-0000000000b1',
    gates: [{ gate: 'CORE_REACHABLE', status: 'PENDING', updatedAt: '2026-11-16T08:25:00Z' }],
    ready: false,
    createdAt: '2026-11-16T08:25:00Z',
    updatedAt: '2026-11-16T08:25:00Z',
    pendingApprovalId: '00000000-0000-0000-0000-0000000000c7',
  },
];

export const fixtures: FixtureSet = {
  'plugins.governance.types': {
    default: (req: FixtureRequest): PluginTypeRow[] => {
      forbidUnless(req.permissions, 'plugin.manage');
      return types;
    },
    empty: [],
    error: null,
  },
  'plugins.governance.orgs': {
    default: (req: FixtureRequest): OrgRow[] => {
      forbidUnless(req.permissions, 'plugin.manage');
      return orgs.slice(1); // children of the platform root, never the root itself
    },
    empty: [],
    error: null,
  },
  'plugins.governance.installations': {
    default: (req: FixtureRequest): InstallationRow[] => {
      forbidUnless(req.permissions, 'plugin.manage');
      const orgId = req.params.orgId ?? '';
      return installs.filter((i) => i.tenantId === orgId);
    },
    empty: [],
    error: null,
  },
  'plugins.governance.register': {
    // Easy install: registering is one step — REGISTERED (and installed) at once.
    default: (req: FixtureRequest) => {
      forbidUnless(req.permissions, 'plugin.register');
      const m = JSON.parse(String((req.body as { manifest?: string }).manifest ?? '{}')) as { code?: string; version?: string };
      return { code: m.code ?? 'unknown', version: m.version ?? '0.0.0', status: 'REGISTERED' };
    },
    error: null,
  },
  'plugins.governance.install': {
    default: (req: FixtureRequest) => {
      forbidUnless(req.permissions, 'plugin.install');
      const b = req.body as { code?: string; version?: string };
      return { code: b.code ?? 'unknown', version: b.version ?? '0.0.0', status: 'PROVISIONING' };
    },
    error: null,
  },
};

export const PLUGIN_ROOT_ID = '00000000-0000-0000-0000-0000000000a1';
