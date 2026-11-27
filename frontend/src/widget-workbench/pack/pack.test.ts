import { describe, expect, it } from 'vitest';
import { backendApiPromptSection, checkDataModule, llmInput, parseBackendProposal, parsePack, PROPOSED_CONTROLLER, WORKBENCH_MODE_LINE } from './pack';
import type { BackendApi, Endpoint } from './pack';

const INDEX = `import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { Trail } from './widget';
const w = defineWidget({ id: 'audit.trail', version: '1.0.0', label: 'Audit trail', contexts: ['ANY'], requires: ['audit.view'],
  sources: ['audit.trail.page'], size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) }, render: Trail });
export default w;
`;
const WIDGET = `import { useWidgetRuntime } from '../../core/runtime';
export function Trail() { const { context } = useWidgetRuntime(); return <div>{context.orgId}</div>; }
`;
const DATA = `import type { DataSources } from '../../core/sources';
export const sources: DataSources = { 'audit.trail.page': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/audit', query: ['page'] } } };
`;

function pack(files: Record<string, string>, id = 'audit.trail') {
  return [
    'Here is your widget:',
    '```text',
    '=== FSP-WIDGET-PACK v1 ===',
    `id: ${id}`,
    'label: Audit trail',
    'summary: who did what',
    ...Object.entries(files).flatMap(([n, c]) => [`=== FILE: ${n} ===`, '```tsx', c, '```']),
    '=== END PACK ===',
    '```',
  ].join('\n');
}

const OK = { 'index.tsx': INDEX, 'widget.tsx': WIDGET, 'data.ts': DATA };

describe('parsePack', () => {
  it('parses a pack wrapped in chat fences and strips per-file fences', () => {
    const r = parsePack(pack(OK));
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.pack).toMatchObject({ id: 'audit.trail', area: 'audit', name: 'trail', label: 'Audit trail' });
    expect(r.pack.files.map((f) => f.name)).toEqual(['index.tsx', 'widget.tsx', 'data.ts']);
    expect(r.pack.files[0].content.startsWith('import { defineWidget }')).toBe(true);
    expect(r.pack.files[0].content).not.toContain('```');
  });

  it('rejects a cut-off pack', () => {
    const r = parsePack(pack(OK).replace('=== END PACK ===', ''));
    expect(r.ok).toBe(false);
  });

  it('rejects a bad id and a manifest id mismatch', () => {
    expect(parsePack(pack(OK, 'Audit-Trail')).ok).toBe(false);
    const r = parsePack(pack(OK, 'audit.log'));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.issues.some((i) => i.file === 'index.tsx' && /audit\.log/.test(i.message))).toBe(true);
  });

  it.each([
    ['direct fetch', `export function T() { void fetch('/x'); return null; }`],
    ['any', `export function T(p: any) { return p; }`],
    ['storage', `export function T() { localStorage.setItem('a', 'b'); return null; }`],
    ['foreign import', `import axios from 'axios';\nexport function T() { return axios; }`],
    ['escaping import', `import x from '../../../../secrets';\nexport const T = x;`],
    ['absolute url', `export const u = 'https://evil.example';`],
    ['raw html', `export function T() { return <div dangerouslySetInnerHTML={{ __html: '' }} />; }`],
    ['api path outside data.ts', `export const p = '/api/tenants';`],
    ['hex colour', `export function T() { return <div className="text-[#ff0000]" />; }`],
    ['dynamic import', `export const m = () => import('./widget');`],
  ])('rejects %s', (_label, code) => {
    const r = parsePack(pack({ ...OK, 'widget.tsx': WIDGET + code + '\n' }));
    expect(r.ok).toBe(false);
  });

  it('rejects folders in file names and missing required files', () => {
    expect(parsePack(pack({ ...OK, '../evil.ts': 'export {}' })).ok).toBe(false);
    const { 'data.ts': _drop, ...noData } = OK;
    void _drop;
    expect(parsePack(pack(noData)).ok).toBe(false);
  });

  it('allows comments mentioning forbidden things', () => {
    const r = parsePack(pack({ ...OK, 'widget.tsx': `// never call fetch() here\n${WIDGET}` }));
    expect(r.ok).toBe(true);
  });
});

describe('checkDataModule', () => {
  const endpoints: Endpoint[] = [
    { id: 'audit.list', method: 'GET', path: '/api/tenants/{tenantId}/audit', permission: 'audit.view', risk: 'READ', title: '' },
    { id: 'user.update', method: 'PATCH', path: '/api/tenants/{tenantId}/users/{personId}', permission: 'user.manage', risk: 'WRITE', title: '' },
  ];

  it('accepts existing endpoints with matching templates (param names may differ)', () => {
    expect(checkDataModule('audit.trail', {
      sources: { 'audit.trail.page': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/audit', query: ['page'] } } },
      commands: { 'audit.trail.fix': { '*': { method: 'PATCH', path: '/api/tenants/{tenantId}/users/{id}' } } },
    }, endpoints)).toEqual([]);
  });

  it('rejects unknown endpoints, wrong prefixes, inline queries and wrong verbs', () => {
    const issues = checkDataModule('audit.trail', {
      sources: {
        'other.name': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/audit' } },
        'audit.trail.q': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/audit?size=5' } },
        'audit.trail.post': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/audit' } },
        'audit.trail.secret': { '*': { method: 'GET', path: '/api/internal/keys' } },
      },
    }, endpoints);
    expect(issues.map((i) => i.message).join('\n')).toMatch(/must be named "audit\.trail\./);
    expect(issues.some((i) => /no "\?"/.test(i.message))).toBe(true);
    expect(issues.some((i) => /sources are GET/.test(i.message))).toBe(true);
    expect(issues.some((i) => /\/api\/internal\/keys is not an existing endpoint/.test(i.message))).toBe(true);
  });
});

describe('llmInput', () => {
  it('puts the skill first, then the requirement, then the answer rule', () => {
    const t = llmInput('# SKILL\nrules', '  Show disabled users  ');
    expect(t.indexOf('# SKILL')).toBe(0);
    expect(t).toContain('# Requirement\n\nShow disabled users\n');
    expect(t.indexOf('Show disabled users')).toBeLessThan(t.indexOf('Follow the fsp-widget-author skill'));
  });

  it('carries the workbench mode line so the skill answers with a pack, not files', () => {
    const t = llmInput('# SKILL\nrules', 'Show disabled users');
    expect(t).toContain(`${WORKBENCH_MODE_LINE}\n`);
    expect(t.indexOf(WORKBENCH_MODE_LINE)).toBeLessThan(t.indexOf('# Requirement'));
  });

  it('includes the live backend API catalog between skill and requirement when synced', () => {
    const catalog: BackendApi[] = [
      {
        controller: 'LedgerController', method: 'books', httpMethod: 'GET',
        path: '/v1/tenants/{tenantId}/books', operationId: 'ledger.book.list',
        permission: 'gl.account.view', component: 'ledger', risk: 'READ', title: 'List books',
        params: [{ name: 'tenantId', type: 'UUID', location: 'path', required: true }],
      },
      {
        controller: 'LedgerController', method: 'provisionDefaultCoa', httpMethod: 'POST',
        path: '/v1/tenants/{tenantId}/books/{bookId}/provision-default-coa',
        operationId: 'ledger.coa.provision', permission: 'gl.account.create', component: 'ledger',
        risk: 'WRITE', title: 'Provision default CoA', params: [],
      },
    ];
    const t = llmInput('# SKILL\nrules', '  Show my books  ', catalog);
    expect(t.indexOf('# SKILL')).toBe(0);
    expect(t).toContain('## Backend APIs the system provides right now');
    expect(t).toContain('GET /v1/tenants/{tenantId}/books — LedgerController.books');
    expect(t).toContain('PATH tenantId: UUID');
    expect(t).toContain('POST /v1/tenants/{tenantId}/books/{bookId}/provision-default-coa');
    expect(t.indexOf('## Backend APIs')).toBeLessThan(t.indexOf('# Requirement'));
    expect(t).toContain('# Requirement\n\nShow my books\n');
    expect(t).toContain('NEEDS BACKEND');
    expect(t).toContain('=== BACKEND PROPOSAL ===');
  });

  it('omits the catalog section when no catalog was synced', () => {
    const t = llmInput('# SKILL\nrules', 'Show disabled users');
    expect(t).not.toContain('## Backend APIs the system provides right now');
    expect(t.indexOf('# Requirement')).toBeGreaterThan(t.indexOf('# SKILL'));
  });
});

describe('backendApiPromptSection', () => {
  const catalog: BackendApi[] = [
    {
      controller: 'XController', method: 'list', httpMethod: 'GET',
      path: '/v1/x/{id}', operationId: 'x.list', permission: 'x.view', component: 'x',
      risk: 'READ', title: 'List X', params: [
        { name: 'id', type: 'UUID', location: 'path', required: true },
        { name: 'page', type: 'int', location: 'query', required: false },
      ],
    },
  ];
  it('renders method, path, metadata and params', () => {
    const t = backendApiPromptSection(catalog);
    expect(t).toContain('GET /v1/x/{id} — XController.list [op: x.list · perm: x.view · risk: READ]');
    expect(t).toContain('PATH id: UUID');
    expect(t).toContain('QUERY page: int (optional)');
  });
  it('handles empty catalog', () => {
    expect(backendApiPromptSection([])).toContain('## Backend APIs the system provides right now');
  });
  it('lists proposed (unbuilt) APIs apart, never as callable', () => {
    const t = backendApiPromptSection([
      ...catalog,
      { controller: PROPOSED_CONTROLLER, method: 'handler', httpMethod: 'GET', path: '/v1/inventory/low-stock',
        operationId: null, permission: null, component: null, risk: null, title: 'low stock', params: [] },
    ]);
    const callable = t.slice(0, t.indexOf('### Proposed earlier'));
    expect(callable).toContain('GET /v1/x/{id}');
    expect(callable).not.toContain('/v1/inventory/low-stock');
    expect(t).toContain('NOT built yet');
    expect(t).toContain('- GET /v1/inventory/low-stock — low stock');
  });
  it('tells the LLM the live list supersedes §8 and to use /api paths', () => {
    const t = backendApiPromptSection(catalog);
    expect(t).toContain('SUPERSEDES');
    expect(t).toContain('/api/ instead of /v1/');
  });
});

describe('parseBackendProposal', () => {
  const reply = `
Some chat text
=== BACKEND PROPOSAL ===
NEW APIS:
- POST /v1/inventory/low-stock — purpose: list items below min
  request: { tenantId: UUID }
  response: [ { sku, qty } ]
  controller: InventoryController
  method: lowStock
- GET /v1/inventory/{sku} — purpose: read one item
SERVER SIDE:
- backend/core/src/main/java/com/fsp/core/inventory/InventoryController.java — add lowStock method
=== END BACKEND PROPOSAL ===
more chat
`;
  it('parses new APIs and server-side modifications', () => {
    const r = parseBackendProposal(reply);
    expect(r.ok).toBe(true);
    expect(r.proposal).toBeDefined();
    if (!r.proposal) return;
    expect(r.proposal.newApis).toEqual([
      {
        httpMethod: 'POST', path: '/v1/inventory/low-stock', controller: 'InventoryController',
        method: 'lowStock', operationId: null, purpose: 'list items below min',
        request: '{ tenantId: UUID }', response: '[ { sku, qty } ]',
      },
      { httpMethod: 'GET', path: '/v1/inventory/{sku}', controller: null, method: null,
        operationId: null, purpose: 'read one item', request: null, response: null },
    ]);
    expect(r.proposal.serverSide).toEqual([
      'backend/core/src/main/java/com/fsp/core/inventory/InventoryController.java — add lowStock method',
    ]);
  });
  it('no section is not an error', () => {
    expect(parseBackendProposal('just a widget pack')).toEqual({ ok: true });
  });
  it('missing end marker is flagged but still parsed', () => {
    const r = parseBackendProposal('x\n=== BACKEND PROPOSAL ===\nNEW APIS:\n- GET /v1/a\n');
    expect(r.ok).toBe(true);
    expect(r.issues).toHaveLength(1);
    if (r.proposal) expect(r.proposal.newApis).toHaveLength(1);
  });
});
