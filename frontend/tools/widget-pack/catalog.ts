/**
 * Code-derived backend catalog — the ONE list of endpoints a portal widget may call, read
 * straight from the source tree (no running stack needed):
 *
 *   - core: every `@FspOperation` handler in backend/core (class + method mappings, constants
 *     resolved, path/query/body params, return type);
 *   - plugins: every backend/plugins/<code>/plugin-manifest.json `operations[]` entry.
 *
 * Used by: the workbench pack validator (vite-plugin stage), `bun run widgets:skill` (the
 * generated chat-LLM skill + endpoints.json), and the agent tooling (`widgets:survey`,
 * `widgets:verify`, `widgets:stage`). Because all three read the same scan, the prompt, the
 * validator and the agent can never disagree about what exists.
 */
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface CatalogParam {
  name: string;
  type: string;
  location: 'path' | 'query' | 'body' | 'header';
  required: boolean;
}

export interface CatalogOp {
  id: string;
  method: HttpMethod;
  /** gateway path, /v1/... */
  path: string;
  /** BFF path the browser calls, /api/... */
  api: string;
  permission: string;
  risk: string;
  title: string;
  component: string;
  /** 'core' | 'plugin:<code>' */
  origin: string;
  /** repo-relative file[:line] of the handler (or manifest) */
  where: string;
  handler: string | null;
  params: CatalogParam[];
  body: string | null;
  returns: string | null;
}

/** Endpoint shape the pack validator + endpoints.json use (unchanged wire format). */
export interface Endpoint {
  id: string;
  method: HttpMethod;
  path: string;
  permission: string;
  risk: string;
  title: string;
}

/** Machine-only, platform-plumbing or builder operations a portal widget must never call. */
export const EXCLUDED: readonly RegExp[] = [
  /^approval\.open$/, /^approval\.plugin\./, /^plugin\.events?\b/, /^plugin\.installation\.(provisioning|ready)$/,
  /^tenant\.verification\./, /^tenant\.child\.create$/, /^ui\./, /^plugin\.register$/, /^plugin\.type\./,
  /^tenant\.act-as\./,
];

export const widgetCallable = (op: Pick<CatalogOp, 'id' | 'path'>): boolean =>
  op.path.startsWith('/v1/') && !EXCLUDED.some((r) => r.test(op.id));

export const toApi = (v1: string): string => v1.replace(/^\/v1\//, '/api/');

/* ------------------------------------------------------------------ java text helpers */

function walk(dir: string, out: string[] = []): string[] {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'build' || e.name === 'target' || e.name === 'node_modules' || e.name.startsWith('.')) continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

/** Index of the char that closes the bracket opened at `open` (strings and char literals skipped). */
function closeOf(text: string, open: number): number {
  const pairs: Record<string, string> = { '(': ')', '{': '}', '<': '>', '[': ']' };
  const o = text[open];
  const c = pairs[o];
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    const ch = text[i];
    if (ch === '"' || ch === "'") {
      i++;
      while (i < text.length && text[i] !== ch) i += text[i] === '\\' ? 2 : 1;
      continue;
    }
    if (ch === o) depth++;
    else if (ch === c && --depth === 0) return i;
  }
  return -1;
}

/** Split at depth-0 commas (parens, generics, braces and strings respected). */
function splitTop(s: string): string[] {
  const out: string[] = [];
  let depth = 0;
  let cur = '';
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === '"') {
      let j = i + 1;
      while (j < s.length && s[j] !== '"') j += s[j] === '\\' ? 2 : 1;
      cur += s.slice(i, j + 1);
      i = j;
      continue;
    }
    if ('(<{['.includes(ch)) depth++;
    if (')>}]'.includes(ch)) depth--;
    if (ch === ',' && depth === 0) {
      out.push(cur.trim());
      cur = '';
    } else cur += ch;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' ')).replace(/(^|[^:"])\/\/[^\n]*/g, '$1');
}

/** `static final String X = "..."` across the tree → { X, Class.X }. */
function constants(files: string[]): Map<string, string> {
  const m = new Map<string, string>();
  for (const f of files) {
    const src = readFileSync(f, 'utf8');
    const cls = /(?:class|interface|enum|record)\s+(\w+)/.exec(src)?.[1] ?? '';
    for (const c of src.matchAll(/static\s+final\s+String\s+(\w+)\s*=\s*((?:"[^"]*"\s*\+?\s*)+);/g)) {
      const v = [...c[2].matchAll(/"([^"]*)"/g)].map((x) => x[1]).join('');
      m.set(`${cls}.${c[1]}`, v);
      if (!m.has(c[1])) m.set(c[1], v);
      m.set(`${f}#${c[1]}`, v); // file-local wins
    }
  }
  return m;
}

function resolveExpr(expr: string, consts: Map<string, string>, file: string): string {
  return expr.split('+').map((part) => {
    const p = part.trim();
    const lit = /^"([^"]*)"$/.exec(p);
    if (lit) return lit[1];
    return consts.get(`${file}#${p}`) ?? consts.get(p) ?? consts.get(p.split('.').slice(-2).join('.')) ?? '';
  }).join('');
}

/** Annotation args → { value/path/positional, key=value... } (raw expressions). */
function annoArgs(args: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of splitTop(args)) {
    const kv = /^(\w+)\s*=\s*([\s\S]+)$/.exec(part);
    if (kv) out[kv[1]] = kv[2].trim();
    else if (part) out['value'] = part;
  }
  return out;
}

const lastSeg = (s: string) => s.split('.').pop() ?? s;

const MAPPINGS: Record<string, HttpMethod | null> = {
  GetMapping: 'GET', PostMapping: 'POST', PutMapping: 'PUT', PatchMapping: 'PATCH', DeleteMapping: 'DELETE', RequestMapping: null,
};

function mappingPath(args: string | null, consts: Map<string, string>, file: string): string {
  if (!args) return '';
  const a = annoArgs(args);
  let e = a['value'] ?? a['path'] ?? '';
  if (e.startsWith('{')) e = splitTop(e.slice(1, -1))[0] ?? '';
  return resolveExpr(e, consts, file);
}

function join2(base: string, sub: string): string {
  if (!base) return sub || '/';
  if (!sub) return base;
  return `${base.replace(/\/$/, '')}/${sub.replace(/^\//, '')}`;
}

interface Anno { name: string; args: string | null; start: number; end: number }

/** Annotations in `text` from `from`, stopping at the first non-annotation token. */
function annotationsAt(text: string, from: number): { annos: Anno[]; next: number } {
  const annos: Anno[] = [];
  let i = from;
  for (;;) {
    while (i < text.length && /\s/.test(text[i])) i++;
    if (text[i] !== '@') break;
    const m = /^@([\w.]+)/.exec(text.slice(i));
    if (!m) break;
    let end = i + m[0].length;
    let args: string | null = null;
    let j = end;
    while (j < text.length && /\s/.test(text[j])) j++;
    if (text[j] === '(') {
      const c = closeOf(text, j);
      args = text.slice(j + 1, c);
      end = c + 1;
    }
    annos.push({ name: lastSeg(m[1]), args, start: i, end });
    i = end;
  }
  return { annos, next: i };
}

function parseParams(list: string): { params: CatalogParam[]; body: string | null } {
  const params: CatalogParam[] = [];
  let body: string | null = null;
  for (const raw of splitTop(list)) {
    const { annos, next } = annotationsAt(raw, 0);
    const rest = raw.slice(next).replace(/\bfinal\s+/, '').trim();
    const m = /^([\s\S]+?)\s+(\w+)$/.exec(rest);
    if (!m) continue;
    const type = m[1].replace(/\s+/g, ' ');
    const name = m[2];
    for (const a of annos) {
      const args = a.args ? annoArgs(a.args) : {};
      const named = (args['value'] ?? args['name'] ?? '').replace(/"/g, '') || name;
      const required = (args['required'] ?? 'true').trim() !== 'false' && !('defaultValue' in args);
      if (a.name === 'PathVariable') params.push({ name: named, type, location: 'path', required: true });
      else if (a.name === 'RequestParam') params.push({ name: named, type, location: 'query', required });
      else if (a.name === 'RequestHeader') params.push({ name: named, type, location: 'header', required });
      else if (a.name === 'RequestBody') {
        body = type;
        params.push({ name, type, location: 'body', required });
      }
    }
  }
  return { params, body };
}

/* ------------------------------------------------------------------ scan */

let cache: { key: string; ops: CatalogOp[] } | null = null;

/** Newest mtime of the inputs — lets long-lived callers (the vite plugin) rescan only on change. */
function stamp(files: string[]): string {
  let newest = 0;
  for (const f of files) newest = Math.max(newest, statSync(f).mtimeMs);
  return `${files.length}:${newest}`;
}

export function scanCatalog(repoRoot: string): CatalogOp[] {
  const coreDir = join(repoRoot, 'backend', 'core', 'src', 'main', 'java');
  const pluginsDir = join(repoRoot, 'backend', 'plugins');
  const javaFiles = walk(coreDir);
  const manifests = existsSync(pluginsDir)
    ? readdirSync(pluginsDir).map((d) => join(pluginsDir, d, 'plugin-manifest.json')).filter(existsSync)
    : [];
  const key = stamp([...javaFiles, ...manifests]);
  if (cache?.key === key) return cache.ops;

  const consts = constants(javaFiles);
  const ops: CatalogOp[] = [];

  for (const file of javaFiles) {
    const raw = readFileSync(file, 'utf8');
    if (!raw.includes('@FspOperation') || !/@(Rest)?Controller\b/.test(raw)) continue;
    const src = stripComments(raw);
    const rel = relative(repoRoot, file);
    const classIdx = src.search(/\bclass\s+\w+/);
    const className = /\bclass\s+(\w+)/.exec(src)?.[1] ?? '';
    const header = src.slice(0, classIdx);
    const classMap = /@RequestMapping\s*\(/.exec(header);
    const base = classMap ? mappingPath(header.slice(classMap.index + classMap[0].length, closeOf(header, classMap.index + classMap[0].length - 1)), consts, file) : '';

    // every member: an annotation run followed by a method signature
    const re = /@(?:Get|Post|Put|Patch|Delete|Request)Mapping\b/g;
    const seen = new Set<number>();
    for (let m = re.exec(src); m; m = re.exec(src)) {
      if (m.index < classIdx) continue;
      // walk back to the start of this member's annotation run
      let s = m.index;
      for (;;) {
        const before = src.slice(0, s).replace(/\s+$/, '');
        const prev = before.length;
        // previous token ends an annotation if it is ')' of an @X(...) or an @Name
        if (before.endsWith(')')) {
          let depth = 0;
          let k = prev - 1;
          for (; k >= 0; k--) {
            if (src[k] === ')') depth++;
            else if (src[k] === '(' && --depth === 0) break;
          }
          const name = /@[\w.]+\s*$/.exec(src.slice(0, k));
          if (name) { s = k - name[0].length; continue; }
        }
        const bare = /@[\w.]+$/.exec(before);
        if (bare) { s = prev - bare[0].length; continue; }
        break;
      }
      if (seen.has(s)) continue;
      seen.add(s);
      const { annos, next } = annotationsAt(src, s);
      const op = annos.find((a) => a.name === 'FspOperation');
      const map = annos.find((a) => a.name in MAPPINGS);
      if (!op || !map) continue;
      const paren = src.indexOf('(', next);
      const sig = src.slice(next, paren).replace(/\s+/g, ' ').trim();
      const sigM = /^(?:(?:public|protected|private|static|final|synchronized)\s+)*(.+)\s+(\w+)$/.exec(sig);
      const list = src.slice(paren + 1, closeOf(src, paren));
      const { params, body } = parseParams(list);

      let method = MAPPINGS[map.name];
      if (!method && map.args) {
        const rm = /RequestMethod\.(\w+)/.exec(map.args);
        method = (rm?.[1] as HttpMethod | undefined) ?? null;
      }
      if (!method) continue;
      const path = join2(base, mappingPath(map.args, consts, file));
      const a = annoArgs(op.args ?? '');
      const str = (k: string) => (a[k] ? resolveExpr(a[k], consts, file) : '');
      ops.push({
        id: str('id'),
        method,
        path,
        api: toApi(path),
        permission: str('permission'),
        risk: a['risk'] ? lastSeg(a['risk']) : 'READ',
        title: str('title'),
        component: str('component'),
        origin: 'core',
        where: `${rel}:${src.slice(0, m.index).split('\n').length}`,
        handler: `${className}.${sigM?.[2] ?? '?'}`,
        params,
        body,
        returns: sigM?.[1] ?? null,
      });
    }
  }

  for (const mf of manifests) {
    const j = JSON.parse(readFileSync(mf, 'utf8')) as { code: string; operations?: { id: string; method: string; path: string; permission: string; title?: string }[] };
    for (const o of j.operations ?? []) {
      const method = o.method.toUpperCase() as HttpMethod;
      ops.push({
        id: o.id, method, path: o.path, api: toApi(o.path), permission: o.permission ?? '',
        risk: method === 'GET' ? 'READ' : 'WRITE', title: o.title ?? `${j.code} plugin: ${o.id}`,
        component: j.code, origin: `plugin:${j.code}`, where: relative(repoRoot, mf), handler: null,
        params: [...o.path.matchAll(/\{(\w+)\}/g)].map((p) => ({ name: p[1], type: 'String', location: 'path' as const, required: true })),
        body: null, returns: null,
      });
    }
  }

  ops.sort((x, y) => x.api.localeCompare(y.api) || x.method.localeCompare(y.method));
  cache = { key, ops };
  return ops;
}

/** The widget-callable subset in the validator's Endpoint shape. */
export function endpointsOf(ops: readonly CatalogOp[]): Endpoint[] {
  return ops.filter(widgetCallable).map((o) => ({
    id: o.id, method: o.method, path: o.api, permission: o.permission, risk: o.risk, title: o.title,
  }));
}

export function digestOf(endpoints: readonly Endpoint[]): string {
  return createHash('sha256').update(JSON.stringify(endpoints)).digest('hex');
}

/** Path templates are equal when they match segment by segment ({x} matches any {y}). */
export function sameTemplate(a: string, b: string): boolean {
  const sa = a.split('?')[0].split('/');
  const sb = b.split('?')[0].split('/');
  if (sa.length !== sb.length) return false;
  return sa.every((s, i) => (s.startsWith('{') && sb[i].startsWith('{')) || s === sb[i]);
}

export function findOp(ops: readonly CatalogOp[], method: string, apiPath: string): CatalogOp | undefined {
  return ops.find((o) => o.method === method && sameTemplate(o.api, apiPath));
}

/* ------------------------------------------------------------------ DTO lookup */

export interface DtoShape {
  name: string;
  kind: 'record' | 'enum' | 'class';
  where: string;
  fields: { name: string; type: string; notes: string[] }[];
  values?: string[];
}

/** Find a record / enum / class by simple or dotted name (`JournalDtos.EntryView`) under backend/. */
export function findDto(repoRoot: string, name: string): DtoShape | null {
  const simple = lastSeg(name);
  const outer = name.includes('.') ? name.split('.').slice(-2, -1)[0] : null;
  const files = walk(join(repoRoot, 'backend'));
  const ranked = outer ? [...files.filter((f) => f.endsWith(`/${outer}.java`)), ...files] : files;
  for (const f of ranked) {
    const raw = readFileSync(f, 'utf8');
    if (!raw.includes(simple)) continue;
    const src = stripComments(raw);
    const rel = relative(repoRoot, f);
    const rec = new RegExp(`\\brecord\\s+${simple}\\s*(<[^>]*>)?\\s*\\(`).exec(src);
    if (rec) {
      const open = rec.index + rec[0].length - 1;
      const fields = splitTop(src.slice(open + 1, closeOf(src, open))).map((p) => {
        const { annos, next } = annotationsAt(p, 0);
        const m = /^([\s\S]+?)\s+(\w+)$/.exec(p.slice(next).trim());
        return { name: m?.[2] ?? p, type: (m?.[1] ?? '?').replace(/\s+/g, ' '), notes: annos.map((a) => `@${a.name}`) };
      });
      return { name, kind: 'record', where: `${rel}:${src.slice(0, rec.index).split('\n').length}`, fields };
    }
    const en = new RegExp(`\\benum\\s+${simple}\\s*\\{`).exec(src);
    if (en) {
      const open = en.index + en[0].length - 1;
      const bodyTxt = src.slice(open + 1, closeOf(src, open)).split(';')[0];
      const values = splitTop(bodyTxt).map((v) => v.replace(/\(.*$/s, '').trim()).filter((v) => /^\w+$/.test(v));
      return { name, kind: 'enum', where: `${rel}:${src.slice(0, en.index).split('\n').length}`, fields: [], values };
    }
    const cl = new RegExp(`\\bclass\\s+${simple}\\b[^{]*\\{`).exec(src);
    if (cl) {
      const open = cl.index + cl[0].length - 1;
      const bodyTxt = src.slice(open + 1, closeOf(src, open));
      const fields = [...bodyTxt.matchAll(/^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public|private|protected)\s+(?!static)(?:final\s+)?([\w.<>, ?[\]]+)\s+(\w+)\s*(?:=|;)/gm)]
        .map((x) => ({ name: x[2], type: x[1].trim(), notes: [] }));
      return { name, kind: 'class', where: `${rel}:${src.slice(0, cl.index).split('\n').length}`, fields };
    }
  }
  return null;
}
