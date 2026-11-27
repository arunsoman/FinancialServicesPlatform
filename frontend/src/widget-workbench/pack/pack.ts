/**
 * FSP widget pack — the text an LLM produces from docs/skills/fsp-widget-author/SKILL.md and
 * a developer pastes into the workbench's "New widget" page. Pure parsing + static checks,
 * shared by the browser (instant feedback) and the dev-only Vite plugin (authoritative,
 * tools/widget-pack/vite-plugin.ts), which also type-checks, lints and loads the code.
 *
 *   === FSP-WIDGET-PACK v1 ===
 *   id: inventory.lowstock
 *   label: Low stock
 *   summary: one line
 *   === FILE: index.tsx ===
 *   …
 *   === FILE: widget.tsx ===
 *   …
 *   === END PACK ===
 *
 * Pack code is UNTRUSTED (LLM output): it may only import the design system + widget runtime,
 * never reach the network directly (all data goes through the port + data.ts mappings), and
 * may only touch its own folder.
 */

export interface PackFile {
  name: string;
  content: string;
}

export interface WidgetPack {
  id: string;
  area: string;
  name: string;
  label: string;
  summary: string;
  files: PackFile[];
}

export interface PackIssue {
  file?: string;
  message: string;
}

export type ParseResult = { ok: true; pack: WidgetPack } | { ok: false; issues: PackIssue[] };

export const PACK_HEADER = '=== FSP-WIDGET-PACK v1 ===';
const FILE_RE = /^=== FILE: (.+?) ===\s*$/;
const END_RE = /^=== END PACK ===\s*$/;

/** "<area>.<name>", lower-case letters/digits, exactly two segments (folder <area>/<name>). */
export const WIDGET_ID_RE = /^[a-z][a-z0-9]{1,30}\.[a-z][a-z0-9]{1,30}$/;
const FILE_NAME_RE = /^[A-Za-z][A-Za-z0-9-]{0,40}\.(tsx|ts)$/;
export const MAX_FILES = 12;
export const MAX_BYTES = 200_000;

/** Module specifiers pack code may import (exact), plus same-folder relatives (./x). */
export const ALLOWED_IMPORTS: readonly string[] = [
  'react',
  'clsx',
  'zod',
  'date-fns',
  'lucide-react',
  '@tanstack/react-query',
  '../../core/manifest',
  '../../core/grid',
  '../../core/runtime',
  '../../core/sources',
  '../../core/ports',
  '../../../ui/atoms',
  '../../../ui/status-maps',
  '../../../web-core/api',
  '../../kit/Drawer',
  '../../kit/FormField',
  '../../kit/SelectField',
  '../../kit/TextAreaField',
  '../../kit/InlineConfirm',
  '../../kit/Notice',
];

/** Things LLM code must never contain (the port + data.ts are the only way to the server). */
const FORBIDDEN: { re: RegExp; why: string }[] = [
  { re: /\bfetch\s*\(/, why: 'direct fetch() — read through port.request(...) instead' },
  { re: /\bXMLHttpRequest\b|\bWebSocket\b|\bEventSource\b|navigator\.sendBeacon/, why: 'direct network access' },
  { re: /\beval\s*\(|\bnew\s+Function\s*\(/, why: 'dynamic code evaluation' },
  { re: /dangerouslySetInnerHTML|\.innerHTML\s*=|\.outerHTML\s*=|insertAdjacentHTML/, why: 'raw HTML injection' },
  { re: /\blocalStorage\b|\bsessionStorage\b|\bindexedDB\b|document\.cookie/, why: 'browser storage / cookies' },
  { re: /window\.location|document\.location|window\.open\s*\(|\bpostMessage\s*\(/, why: 'navigation / cross-window messaging' },
  { re: /import\.meta|\bprocess\.|\brequire\s*\(|\bimport\s*\(/, why: 'environment access or dynamic import' },
  { re: /:\s*any\b|\bas\s+any\b|<any>|any\[\]/, why: 'the any type (use unknown and narrow)' },
  { re: /https?:\/\//, why: 'absolute URL (only /api/... paths in data.ts)' },
  { re: /(className|style)[^\n]*#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(/, why: 'hard-coded colour (use design tokens)' },
];

/** The FORBIDDEN rules + "/api only in data.ts" for one source file — shared with the agent
 *  tooling (tools/widget-agent.ts verify) so hand/agent-written widgets meet the pack bar. */
export function codeRuleIssues(name: string, content: string): PackIssue[] {
  const code = content.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
  const issues: PackIssue[] = [];
  for (const rule of FORBIDDEN) if (rule.re.test(code)) issues.push({ file: name, message: `not allowed: ${rule.why}.` });
  if (name !== 'data.ts' && /['"`]\/api\//.test(code)) {
    issues.push({ file: name, message: '"/api/..." paths belong in data.ts only — components use logical source names.' });
  }
  return issues;
}

const IMPORT_RE = /(?:^|\n)\s*(?:import|export)\s[^'"]*?from\s*['"]([^'"]+)['"]|(?:^|\n)\s*import\s*['"]([^'"]+)['"]/g;

export function folderOf(id: string): { area: string; name: string } {
  const [area, name] = id.split('.');
  return { area, name };
}

/** Strip chat wrapping: a leading ``` fence line and trailing ``` line around a block. */
function unfence(block: string): string {
  const lines = block.replace(/\r\n/g, '\n').split('\n');
  while (lines.length && lines[0].trim() === '') lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  if (lines.length && /^```[\w-]*\s*$/.test(lines[0].trim())) lines.shift();
  if (lines.length && /^```\s*$/.test(lines[lines.length - 1].trim())) lines.pop();
  return lines.join('\n') + '\n';
}

export function parsePack(text: string): ParseResult {
  const issues: PackIssue[] = [];
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const start = lines.findIndex((l) => l.trim() === PACK_HEADER);
  if (start < 0) return { ok: false, issues: [{ message: `No "${PACK_HEADER}" line — paste the whole pack the LLM produced.` }] };

  const meta: Record<string, string> = {};
  const files: PackFile[] = [];
  let current: { name: string; body: string[] } | null = null;
  let ended = false;
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i];
    const f = FILE_RE.exec(line.trim());
    if (f || END_RE.test(line.trim())) {
      if (current) files.push({ name: current.name, content: unfence(current.body.join('\n')) });
      current = f ? { name: f[1].trim(), body: [] } : null;
      if (!f) {
        ended = true;
        break;
      }
      continue;
    }
    if (current) current.body.push(line);
    else {
      const m = /^([a-z]+):\s*(.*)$/.exec(line.trim());
      if (m) meta[m[1]] = m[2].trim();
    }
  }
  if (!ended) issues.push({ message: 'The pack has no "=== END PACK ===" line — it may have been cut off; copy it again.' });

  const id = meta.id ?? '';
  if (!WIDGET_ID_RE.test(id)) issues.push({ message: `id "${id}" must look like "area.name" (lower-case letters and digits, two parts).` });
  if (!meta.label) issues.push({ message: 'label: is missing.' });
  issues.push(...checkFiles(files, id));
  if (issues.length) return { ok: false, issues };
  const { area, name } = folderOf(id);
  return { ok: true, pack: { id, area, name, label: meta.label, summary: meta.summary ?? '', files } };
}

function checkFiles(files: PackFile[], id: string): PackIssue[] {
  const issues: PackIssue[] = [];
  const names = new Set<string>();
  let bytes = 0;
  if (files.length === 0) issues.push({ message: 'The pack has no files.' });
  if (files.length > MAX_FILES) issues.push({ message: `At most ${MAX_FILES} files per widget.` });
  for (const f of files) {
    bytes += f.content.length;
    if (!FILE_NAME_RE.test(f.name)) {
      issues.push({ file: f.name, message: 'File names are plain names in the widget folder (letters, digits, -), ending .ts or .tsx — no folders.' });
      continue;
    }
    if (names.has(f.name)) issues.push({ file: f.name, message: 'Duplicate file.' });
    names.add(f.name);
  }
  for (const f of files) if (FILE_NAME_RE.test(f.name)) issues.push(...checkSource(f, names));
  if (bytes > MAX_BYTES) issues.push({ message: `The pack is larger than ${MAX_BYTES / 1000} kB.` });
  for (const required of ['index.tsx', 'widget.tsx', 'data.ts']) {
    if (!names.has(required)) issues.push({ message: `${required} is required.` });
  }
  const index = files.find((f) => f.name === 'index.tsx');
  if (index && id) {
    if (!index.content.includes('defineWidget(')) issues.push({ file: 'index.tsx', message: 'must call defineWidget({...}).' });
    if (!new RegExp(`id:\\s*['"]${id.replace('.', '\\.')}['"]`).test(index.content)) {
      issues.push({ file: 'index.tsx', message: `the manifest id must be '${id}' (same as the pack header).` });
    }
    if (!/export\s+default\b/.test(index.content)) issues.push({ file: 'index.tsx', message: 'must `export default` the widget.' });
  }
  return issues;
}

function checkSource(f: PackFile, siblings: Set<string>): PackIssue[] {
  const issues: PackIssue[] = [];
  // comments may mention anything; check code only
  const code = f.content.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
  for (const m of code.matchAll(IMPORT_RE)) {
    const spec = m[1] ?? m[2];
    if (!spec) continue;
    if (spec.startsWith('./')) {
      const base = spec.slice(2);
      if (base.includes('/') || ![`${base}.ts`, `${base}.tsx`].some((n) => siblings.has(n))) {
        issues.push({ file: f.name, message: `import "${spec}" — only files in this pack.` });
      }
      continue;
    }
    if (!ALLOWED_IMPORTS.includes(spec)) {
      issues.push({ file: f.name, message: `import "${spec}" is not allowed (design system + widget runtime only).` });
    }
  }
  for (const rule of FORBIDDEN) {
    // data.ts is where /api paths live; absolute URLs are still banned there
    if (rule.re.test(code)) issues.push({ file: f.name, message: `not allowed: ${rule.why}.` });
  }
  if (f.name !== 'data.ts' && /['"`]\/api\//.test(code)) {
    issues.push({ file: f.name, message: '"/api/..." paths belong in data.ts only — components use logical source names.' });
  }
  return issues;
}

/* ------------------------------------------------------------- endpoints */

export interface Endpoint {
  /** operation id from core's catalog or the plugin manifest */
  id: string;
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** BFF path the browser calls, e.g. /api/tenants/{tenantId}/users */
  path: string;
  permission: string;
  risk: string;
  title: string;
}

/* ------------------------------------------------------------- backend API discovery */

/** One handler parameter discovered from a @RestController (Java reflection, -parameters on). */
export interface BackendParam {
  name: string;
  type: string;             // Java simple type, e.g. UUID, String, List<CoaTemplates.Row>
  location: 'path' | 'query' | 'body' | 'header' | 'request';
  required: boolean;
}

/** One live backend endpoint: method + path + signature + @FspOperation metadata. */
export interface BackendApi {
  controller: string;       // e.g. LedgerController
  method: string;           // handler method name
  httpMethod: string;       // GET | POST | PUT | PATCH | DELETE
  path: string;             // /v1/tenants/{tenantId}/books
  operationId: string | null;
  permission: string | null;
  component: string | null;
  risk: string | null;
  title: string | null;
  params: BackendParam[];
}

/** An API the LLM says the system needs but does not yet have. */
export interface ProposedApi {
  httpMethod: string;
  path: string;
  controller: string | null;   // suggested controller class
  method: string | null;       // suggested handler method
  operationId: string | null;
  purpose: string;
  request: string | null;
  response: string | null;
}

/** What the LLM returns when no existing endpoint fits the requirement: new APIs needed
 *  plus where the server-side code should be modified. */
export interface BackendProposal {
  newApis: ProposedApi[];
  serverSide: string[];
  note?: string | null;
}

export interface DataRequest {
  method: string;
  path: string;
  query?: readonly string[];
}

/** Path templates are equal when they match segment by segment ({x} matches any {y}). */
function sameTemplate(a: string, b: string): boolean {
  const sa = a.split('/');
  const sb = b.split('/');
  if (sa.length !== sb.length) return false;
  return sa.every((s, i) => (s.startsWith('{') && sb[i].startsWith('{')) || s === sb[i]);
}

/**
 * Validates a loaded data.ts against the endpoint catalog: every entry must be named
 * "<widget id>.<thing>", call an existing operation (method + path template), and never
 * carry its own query string (use `query: [...]` allow-lists).
 */
export function checkDataModule(
  id: string,
  mod: { sources?: Record<string, Record<string, DataRequest>>; commands?: Record<string, Record<string, DataRequest>> },
  endpoints: readonly Endpoint[],
): PackIssue[] {
  const issues: PackIssue[] = [];
  const check = (kind: 'sources' | 'commands', entries: Record<string, Record<string, DataRequest>> | undefined) => {
    for (const [name, perPortal] of Object.entries(entries ?? {})) {
      if (!name.startsWith(`${id}.`)) issues.push({ file: 'data.ts', message: `${kind} "${name}" must be named "${id}.<something>".` });
      for (const [portal, req] of Object.entries(perPortal)) {
        const where = `${kind} "${name}" [${portal}]`;
        if (!req || typeof req.path !== 'string' || typeof req.method !== 'string') {
          issues.push({ file: 'data.ts', message: `${where}: needs { method, path }.` });
          continue;
        }
        if (kind === 'sources' && req.method !== 'GET') issues.push({ file: 'data.ts', message: `${where}: sources are GET; writes go in commands.` });
        if (kind === 'commands' && req.method === 'GET') issues.push({ file: 'data.ts', message: `${where}: commands are writes (POST/PUT/PATCH/DELETE).` });
        if (req.path.includes('?')) issues.push({ file: 'data.ts', message: `${where}: no "?" in path — list allowed params in query: [...].` });
        const hit = endpoints.find((e) => e.method === req.method && sameTemplate(e.path, req.path.split('?')[0]));
        if (!hit) issues.push({ file: 'data.ts', message: `${where}: ${req.method} ${req.path} is not an existing endpoint (see the skill's endpoint list).` });
      }
    }
  };
  check('sources', mod.sources);
  check('commands', mod.commands);
  return issues;
}

/** Marks catalog rows the LLM proposed (NEEDS BACKEND) — shown apart, never as callable. */
export const PROPOSED_CONTROLLER = '(proposed)';

/** The skill switches to pack-only output when it sees this line (see .agents/skills/fsp-widget). */
export const WORKBENCH_MODE_LINE = 'MODE: WORKBENCH-PACK';

/** Renders the live backend API catalog (paths + signatures) as text the LLM can match against. */
export function backendApiPromptSection(catalog: readonly BackendApi[]): string {
  const lines: string[] = [];
  const built = catalog.filter((a) => a.controller !== PROPOSED_CONTROLLER);
  const proposed = catalog.filter((a) => a.controller === PROPOSED_CONTROLLER);
  lines.push('## Backend APIs the system provides right now');
  lines.push(
    'These are ALL backend endpoints the widget may call (via data.ts sources/commands). This live list SUPERSEDES',
    'the skill\'s §8 table. Paths are gateway paths: in data.ts write them with /api/ instead of /v1/.',
    'Match the requirement to ONE of them:');
  lines.push('');
  for (const api of built) {
    const meta = [
      api.operationId ? `op: ${api.operationId}` : null,
      api.permission ? `perm: ${api.permission}` : null,
      api.risk ? `risk: ${api.risk}` : null,
    ].filter(Boolean).join(' · ');
    lines.push(`- ${api.httpMethod} ${api.path} — ${api.controller}.${api.method}${meta ? ` [${meta}]` : ''}`);
    if (api.params.length) {
      for (const p of api.params) {
        lines.push(`    · ${p.location.toUpperCase()} ${p.name}: ${p.type}${p.required ? '' : ' (optional)'}`);
      }
    } else {
      lines.push('    · no parameters');
    }
  }
  if (proposed.length) {
    lines.push('');
    lines.push('### Proposed earlier — NOT built yet (never use these in data.ts; the validator rejects them)');
    for (const api of proposed) lines.push(`- ${api.httpMethod} ${api.path}${api.title ? ` — ${api.title}` : ''}`);
  }
  lines.push('');
  lines.push('Rule: if an endpoint above can do the job, use it (method + path) in data.ts and answer with the widget pack.');
  lines.push('If NO existing endpoint fits, you must answer with:');
  lines.push('  1. NEEDS BACKEND — what is missing and why; AND');
  lines.push('  2. ALL new APIs the system needs, each with method + path + purpose + request shape + response shape; AND');
  lines.push('  3. where the server-side code should be modified (which controller class/file and what method to add).');
  lines.push('Put items 2–3 under:');
  lines.push('=== BACKEND PROPOSAL ===');
  lines.push('NEW APIS:');
  lines.push('  - GET /v1/... — purpose: ...  request: ...  response: ...');
  lines.push('SERVER SIDE:');
  lines.push('  - backend/core/.../SomeController.java — add method ...');
  lines.push('=== END BACKEND PROPOSAL ===');
  return lines.join('\n');
}

/**
 * Parses an optional `=== BACKEND PROPOSAL === ... === END BACKEND PROPOSAL ===` section
 * out of an LLM reply: new APIs the system needs + server-side code modifications.
 * Absence of the section is not an error.
 */
export function parseBackendProposal(text: string): { ok: boolean; proposal?: BackendProposal; issues?: PackIssue[] } {
  const issues: PackIssue[] = [];
  const start = text.search(/^=== BACKEND PROPOSAL ===\s*$/m);
  if (start < 0) return { ok: true };
  const rest = text.slice(start + 1);
  const end = rest.search(/^=== END BACKEND PROPOSAL ===\s*$/m);
  const body = end < 0 ? rest : rest.slice(0, end);
  if (end < 0) issues.push({ message: 'Backend proposal has no "=== END BACKEND PROPOSAL ===" line.' });

  const newApis: ProposedApi[] = [];
  const serverSide: string[] = [];
  let section: 'newApis' | 'serverSide' | null = null;
  let current: ProposedApi | null = null;
  const flush = () => { if (current) { newApis.push(current); } current = null; };
  for (const raw of body.split('\n')) {
    const line = raw.trim();
    if (/^NEW APIS?:?$/i.test(line)) { section = 'newApis'; continue; }
    if (/^SERVER SIDE:?$/i.test(line)) { flush(); section = 'serverSide'; continue; }
    if (line.startsWith('===')) break;
    if (section === 'serverSide' && line) {
      serverSide.push(line.replace(/^[-*–]\s*/, ''));
      continue;
    }
    if (section === 'newApis') {
      const m = /^[-*–]\s*(GET|POST|PUT|PATCH|DELETE)\s+(\S+)(?:\s*[—-]\s*(.+))?$/i.exec(line);
      if (m) {
        flush();
        current = {
          httpMethod: m[1].toUpperCase(),
          path: m[2],
          controller: null,
          method: null,
          operationId: null,
          purpose: (m[3] ?? '').trim().replace(/^purpose:\s*/i, ''),
          request: null,
          response: null,
        };
        continue;
      }
      if (current) {
        const kv = /^(\w+):\s*(.*)$/s.exec(line);
        if (kv) {
          const key = kv[1].toLowerCase();
          const val = kv[2].trim();
          if (key === 'purpose') current.purpose = val;
          else if (key === 'request') current.request = val;
          else if (key === 'response') current.response = val;
          else if (key === 'controller') current.controller = val;
          else if (key === 'method') current.method = val;
          else if (key === 'op') current.operationId = val;
          continue;
        }
      }
    }
  }
  flush();
  return {
    ok: true,
    proposal: { newApis, serverSide },
    issues: issues.length ? issues : undefined,
  };
}

/** The text the user pastes into an LLM: the skill, then the live backend API catalog,
 *  then the requirement, then the answer rule. Only active after a successful sync. */
export function llmInput(skill: string, requirement: string, catalog?: readonly BackendApi[] | null): string {
  const parts: string[] = [];
  parts.push(skill.trim());
  parts.push('');
  parts.push('---');
  parts.push('');
  if (catalog && catalog.length) {
    parts.push(backendApiPromptSection(catalog));
    parts.push('');
    parts.push('---');
    parts.push('');
  }
  parts.push(WORKBENCH_MODE_LINE);
  parts.push('');
  parts.push('# Requirement');
  parts.push('');
  parts.push(requirement.trim());
  parts.push('');
  parts.push('---');
  parts.push('');
  parts.push('Follow the fsp-widget-author skill above exactly. Answer with one sentence and the widget pack (§1 step 4),',
    'or with NEEDS BACKEND (§1 step 2) plus a BACKEND PROPOSAL defining every new API needed and where to',
    'modify the server-side code, or with at most two questions if something essential is ambiguous (§1 step 3).',
    '');
  return parts.join('\n');
}
