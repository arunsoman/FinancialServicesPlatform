/**
 * widget-agent — the tool belt behind the fsp-widget skill's AGENT mode
 * (.agents/skills/fsp-widget/SKILL.md). Every command prints a compact answer so an agent
 * reads one screen instead of whole controllers, DTO files or test logs (full logs land in
 * node_modules/.cache/fsp-widget-agent/<id>/ — open them only when a step fails).
 *
 *   discover (read-only, no stack needed)
 *     survey [terms…] [--all]      backend ops (code-derived) + reuse hints + permissions + widgets + SME
 *     dto <Name…> [--ts] [--depth N] record/enum/class fields of a backend DTO (TS interface with --ts)
 *
 *   stage 1 — UI/UX
 *     plan <id> [--requirement "…"] derive the operations sheet (REUSE / NEW) from the widget's
 *                                  manifest + data.ts; creates/updates .widget-stage.json
 *     verify <id>                   code rules · widgets:check · tsc · eslint · vitest · catalog freshness
 *     e2e <id> --scaffold           write tests/e2e/widgets/<id>.spec.ts from the template
 *     e2e <id> [--lane workbench|portal|all] [--live]   run it, record the result
 *     register <id>                 register with core's widget registry via a BFF session (Playwright)
 *     accept <id> --by "<name>" [--note "…"]   record the user's UI approval → stage 2
 *
 *   stage 2 — backend
 *     done <id>                     final gate: no NEW ops left, UI unchanged since approval,
 *                                   live portal e2e green, generated skill fresh
 *
 *     stage <id>                    where the widget is + what the next gate still needs
 */
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findDto, findOp, scanCatalog, widgetCallable, type CatalogOp } from './widget-pack/catalog';
import { codeRuleIssues } from '../src/widget-workbench/pack/pack';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND = dirname(HERE);
const REPO = dirname(FRONTEND);
const WIDGETS = join(FRONTEND, 'src', 'portals', 'shared', 'widgets');
const CORE_SOURCES = join(WIDGETS, 'core', 'sources.ts');
const CATALOGUE = join(WIDGETS, 'permission-catalogue.ts');
const SME = process.env.FSP_SME_DIR ?? join(REPO, '..', 'sme', 'frontend', 'projects', 'shared', 'widgets');
const CACHE = join(FRONTEND, 'node_modules', '.cache', 'fsp-widget-agent');
const SPA = process.env.FSP_SPA_URL ?? 'http://localhost:5173';
const STAGE_FILE = '.widget-stage.json';

/* ------------------------------------------------------------------ stage file */

type Stage = 'ui' | 'backend' | 'done';
interface StageOp {
  name: string;
  kind: 'source' | 'command';
  method: string;
  path: string;
  status: 'REUSE' | 'NEW';
  opId?: string;
  permission?: string;
  risk?: string;
}
interface Run { at: string; ok: boolean; uiHash: string }
interface StageState {
  id: string;
  requirement?: string;
  stage: Stage;
  ops: StageOp[];
  verify?: Run & { steps: { step: string; ok: boolean }[] };
  registered?: { at: string; version: string; by: string; uiHash: string };
  e2e?: { workbench?: Run; portal?: Run & { live: boolean } };
  acceptance?: { by: string; at: string; note?: string; uiHash: string; contractHash: string; newOps: string[] };
  /** where the portal e2e lane finds the widget (set by the agent once the screen is wired) */
  screen?: { portalPath?: string; screenPath?: string; user?: string };
  createdAt?: string;
  history: { at: string; event: string; detail?: string }[];
  [k: string]: unknown;
}

function folder(id: string): string {
  if (!/^[a-z][a-z0-9]*\.[a-z][a-z0-9-]*$/.test(id)) die(`"${id}" is not a widget id (<area>.<name>, lower case)`);
  const [area, name] = id.split('.');
  return join(WIDGETS, area, name);
}

function load(id: string): StageState | null {
  const f = join(folder(id), STAGE_FILE);
  return existsSync(f) ? (JSON.parse(readFileSync(f, 'utf8')) as StageState) : null;
}

function save(s: StageState): void {
  writeFileSync(join(folder(s.id), STAGE_FILE), JSON.stringify(s, null, 2) + '\n');
}

function event(s: StageState, ev: string, detail?: string): void {
  s.history.push({ at: new Date().toISOString(), event: ev, ...(detail ? { detail } : {}) });
}

function must(id: string): StageState {
  const s = load(id);
  if (!s) die(`${id}: no ${STAGE_FILE} yet — run: bun run widgets:plan ${id}`);
  return s;
}

/* ------------------------------------------------------------------ hashing */

function files(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    if (e.name.startsWith('.')) continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) files(p, out);
    else out.push(p);
  }
  return out;
}

const sha = (parts: string[]) => {
  const h = createHash('sha256');
  for (const p of parts) h.update(p).update('\0');
  return h.digest('hex').slice(0, 16);
};

/** Everything the user sees: every file in the widget folder (dot-files excluded). */
function uiHash(id: string): string {
  const dir = folder(id);
  return sha(files(dir).flatMap((f) => [relative(dir, f), readFileSync(f, 'utf8')]));
}

/** The wire contract: data.ts (sources/commands/fixtures) + model.ts (types), when present. */
function contractHash(id: string): string {
  const dir = folder(id);
  return sha(['data.ts', 'model.ts'].filter((n) => existsSync(join(dir, n))).flatMap((n) => [n, readFileSync(join(dir, n), 'utf8')]));
}

/* ------------------------------------------------------------------ manifest + data mapping */

interface Mapping { name: string; method: string; path: string; where: 'data.ts' | 'core/sources.ts' }

const MAP_RE = /'([a-z][\w.-]*)':\s*\{\s*'\*':\s*\{\s*method:\s*'(\w+)',\s*path:\s*'([^']+)'/g;

function mappings(text: string, where: Mapping['where']): Mapping[] {
  return [...text.matchAll(MAP_RE)].map((m) => ({ name: m[1], method: m[2], path: m[3], where }));
}

function listOf(text: string, key: string): string[] {
  const m = new RegExp(`\\b${key}:\\s*\\[([^\\]]*)\\]`).exec(text);
  return m ? [...m[1].matchAll(/'([^']+)'/g)].map((x) => x[1]) : [];
}

interface Manifest { text: string; sources: string[]; commands: string[]; actions: { id: string; cell: string; command?: string }[]; requires: string[] }

function manifest(id: string): Manifest {
  const f = join(folder(id), 'index.tsx');
  if (!existsSync(f)) die(`${id}: ${relative(REPO, f)} does not exist`);
  const text = readFileSync(f, 'utf8');
  // only inside `actions: [ … ]` — the manifest's own id must never read as an action
  const at = text.search(/\bactions:\s*\[/);
  let block = '';
  if (at >= 0) {
    const open = text.indexOf('[', at);
    let depth = 0;
    for (let i = open; i < text.length; i++) {
      if (text[i] === '[') depth++;
      else if (text[i] === ']' && --depth === 0) { block = text.slice(open, i + 1); break; }
    }
  }
  const actions = [...block.matchAll(/\{[^{}]*?\bid:\s*'([^']+)'[^{}]*?\bcell:\s*'([^']+)'[^{}]*?\}/g)].map((m) => ({
    id: m[1], cell: m[2], command: /command:\s*'([^']+)'/.exec(m[0])?.[1],
  }));
  const req = /requires:\s*(\[[^\]]*\]|\{\s*all:\s*\[[^\]]*\]\s*\})/.exec(text)?.[1] ?? '';
  return {
    text, sources: listOf(text, 'sources'), commands: listOf(text, 'commands'), actions,
    requires: [...req.matchAll(/'([^']+)'/g)].map((x) => x[1]),
  };
}

function deriveOps(id: string, ops: readonly CatalogOp[]): { ops: StageOp[]; problems: string[] } {
  const dir = folder(id);
  const m = manifest(id);
  const data = existsSync(join(dir, 'data.ts')) ? mappings(readFileSync(join(dir, 'data.ts'), 'utf8'), 'data.ts') : [];
  const core = mappings(readFileSync(CORE_SOURCES, 'utf8'), 'core/sources.ts');
  const problems: string[] = [];
  const out: StageOp[] = [];
  const wanted = [
    ...m.sources.map((n) => ({ n, kind: 'source' as const })),
    ...m.commands.map((n) => ({ n, kind: 'command' as const })),
    ...data.filter((d) => !m.sources.includes(d.name) && !m.commands.includes(d.name))
      .map((d) => ({ n: d.name, kind: d.method === 'GET' ? 'source' as const : 'command' as const })),
  ];
  for (const { n, kind } of wanted) {
    const hit = data.find((d) => d.name === n) ?? core.find((d) => d.name === n);
    if (!hit) {
      problems.push(`${kind} "${n}" has no mapping in data.ts or core/sources.ts`);
      continue;
    }
    if (hit.where === 'data.ts' && !n.startsWith(`${id}.`)) problems.push(`data.ts "${n}" must be named "${id}.<thing>"`);
    if (hit.where === 'data.ts' && hit.path.includes('?')) problems.push(`data.ts "${n}": no "?" in path — list params in query: [...]`);
    if (kind === 'source' && hit.method !== 'GET') problems.push(`source "${n}" is ${hit.method} — writes are commands`);
    if (kind === 'command' && hit.method === 'GET') problems.push(`command "${n}" is GET — reads are sources`);
    const op = findOp(ops, hit.method, hit.path);
    if (op && !widgetCallable(op)) problems.push(`"${n}" → ${op.id} is platform plumbing, not widget-callable`);
    out.push({
      name: n, kind, method: hit.method, path: hit.path.split('?')[0], status: op ? 'REUSE' : 'NEW',
      ...(op ? { opId: op.id, permission: op.permission, risk: op.risk } : {}),
    });
  }
  for (const a of m.actions) {
    if (a.command && !m.commands.includes(a.command)) problems.push(`action ${a.id} sends "${a.command}" but manifest commands[] lacks it`);
    if (!m.text.includes(`'${a.id}'`)) problems.push(`action ${a.id}: id not found`);
  }
  return { ops: out, problems };
}

/* ------------------------------------------------------------------ process helpers */

function die(msg: string): never {
  console.error(`✗ ${msg}`);
  process.exit(1);
}

function run(cmd: string[], env: Record<string, string> = {}, cwd = FRONTEND): Promise<{ ok: boolean; out: string }> {
  return new Promise((resolve) => {
    const child = spawn(cmd[0], cmd.slice(1), { cwd, env: { ...process.env, ...env, FORCE_COLOR: '0' } });
    let out = '';
    child.stdout.on('data', (b: Buffer) => { out += b.toString(); });
    child.stderr.on('data', (b: Buffer) => { out += b.toString(); });
    child.on('close', (code) => resolve({ ok: code === 0, out: out.replace(new RegExp(`${String.fromCharCode(27)}\\[[0-9;]*m`, 'g'), '') }));
    child.on('error', (e) => resolve({ ok: false, out: e.message }));
  });
}

function logTo(id: string, step: string, out: string): string {
  const dir = join(CACHE, id);
  mkdirSync(dir, { recursive: true });
  const f = join(dir, `${step}.log`);
  writeFileSync(f, out);
  return relative(FRONTEND, f);
}

function tail(out: string, n = 25): string {
  const lines = out.trim().split('\n');
  return (lines.length > n ? [`… ${lines.length - n} more lines in the log`, ...lines.slice(-n)] : lines).map((l) => `      ${l}`).join('\n');
}

async function reachable(url: string): Promise<boolean> {
  try {
    const r = await fetch(url, { method: 'GET', signal: AbortSignal.timeout(3000) });
    return r.status < 500;
  } catch {
    return false;
  }
}

function flag(args: string[], name: string): string | undefined {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
}

/* ------------------------------------------------------------------ survey + dto */

function survey(args: string[]): void {
  const all = args.includes('--all');
  const terms = args.filter((a) => !a.startsWith('--')).map((t) => t.toLowerCase());
  const ops = scanCatalog(REPO).filter((o) => all || widgetCallable(o));
  const hay = (o: CatalogOp) => `${o.id} ${o.api} ${o.title} ${o.permission} ${o.component}`.toLowerCase();
  const hits = terms.length ? ops.filter((o) => terms.some((t) => hay(o).includes(t))) : ops;

  console.log(`# backend operations${terms.length ? ` matching ${terms.join('|')}` : ''} — ${hits.length}/${ops.length} (code-derived; READ/WRITE/FOUR_EYES = @FspOperation risk)`);
  for (const o of hits) {
    const q = o.params.filter((p) => p.location === 'query').map((p) => p.name + (p.required ? '' : '?'));
    const bits = [
      `${o.method.padEnd(6)} ${o.api}`,
      o.id,
      o.permission ? `perm=${o.permission}` : 'perm=—',
      o.risk,
      o.body ? `body=${o.body}` : '',
      q.length ? `q[${q.join(',')}]` : '',
      o.returns ? `→ ${o.returns}` : '',
      o.handler ? `@${o.handler}` : `@${o.origin}`,
    ].filter(Boolean);
    console.log(`  ${bits.join('  ')}`);
  }

  // reuse hints: logical names already mapped to these endpoints, and the widgets using them
  const core = mappings(readFileSync(CORE_SOURCES, 'utf8'), 'core/sources.ts');
  const widgetDirs = readdirSync(WIDGETS, { withFileTypes: true }).filter((d) => d.isDirectory() && d.name !== 'core' && d.name !== 'kit')
    .flatMap((a) => readdirSync(join(WIDGETS, a.name), { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => join(WIDGETS, a.name, d.name)))
    .filter((d) => existsSync(join(d, 'index.tsx')));
  const local = widgetDirs.flatMap((d) => (existsSync(join(d, 'data.ts')) ? mappings(readFileSync(join(d, 'data.ts'), 'utf8'), 'data.ts') : []));
  const manifests = widgetDirs.map((d) => ({ id: /id:\s*'([^']+)'/.exec(readFileSync(join(d, 'index.tsx'), 'utf8'))?.[1] ?? relative(WIDGETS, d), text: readFileSync(join(d, 'index.tsx'), 'utf8'), dir: d }));
  const reuse: string[] = [];
  for (const o of hits) {
    for (const mp of [...core, ...local].filter((x) => x.method === o.method && findOp([o], x.method, x.path.split('?')[0]))) {
      const users = manifests.filter((w) => w.text.includes(`'${mp.name}'`)).map((w) => w.id);
      reuse.push(`  ${mp.name.padEnd(28)} ${mp.method} ${mp.path}  (${mp.where})${users.length ? `  used by ${users.join(', ')}` : ''}`);
    }
  }
  if (reuse.length) {
    console.log('\n# logical names already mapped (reuse a core/sources.ts name directly; data.ts names are widget-private — copy the mapping under your id)');
    console.log([...new Set(reuse)].join('\n'));
  }

  if (terms.length) {
    const keys = [...readFileSync(CATALOGUE, 'utf8').matchAll(/'([a-z][a-z0-9._]*)'/g)].map((m) => m[1]);
    const pk = [...new Set(keys)].filter((k) => terms.some((t) => k.includes(t)));
    if (pk.length) console.log(`\n# permission keys (permission-catalogue.ts)\n  ${pk.join('  ')}`);
    const ws = manifests.filter((w) => terms.some((t) => w.text.toLowerCase().includes(t)));
    if (ws.length) console.log(`\n# existing widgets mentioning it (extend before you duplicate)\n${ws.map((w) => `  ${w.id}  ${relative(FRONTEND, w.dir)}`).join('\n')}`);
    if (existsSync(SME)) {
      const sme = readdirSync(SME, { withFileTypes: true }).filter((d) => d.isDirectory())
        .flatMap((a) => [a.name, ...readdirSync(join(SME, a.name), { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => `${a.name}/${d.name}`)])
        .filter((p) => terms.some((t) => p.toLowerCase().includes(t)));
      if (sme.length) console.log(`\n# SME reference widgets (port their UX — ${SME})\n  ${sme.join('  ')}`);
    }
  }
}

const JAVA_TS: Record<string, string> = {
  String: 'string', UUID: 'string', Instant: 'string', LocalDate: 'string', LocalDateTime: 'string', OffsetDateTime: 'string',
  int: 'number', long: 'number', double: 'number', float: 'number', short: 'number',
  Integer: 'number', Long: 'number', Double: 'number', BigDecimal: 'string', boolean: 'boolean', Boolean: 'boolean',
};

function tsType(java: string): string {
  const t = java.replace(/@\w+\s*/g, '').trim();
  const list = /^(?:List|Set|Collection)<(.+)>$/.exec(t);
  if (list) return `${tsType(list[1])}[]`;
  if (/^Map</.test(t)) return 'Record<string, unknown>';
  if (t.endsWith('[]')) return `${tsType(t.slice(0, -2))}[]`;
  return JAVA_TS[t] ?? t.split('.').pop() ?? t;
}

function dto(args: string[]): void {
  const asTs = args.includes('--ts');
  const depth = Number(flag(args, '--depth') ?? 1);
  const names = args.filter((a, i) => !a.startsWith('--') && args[i - 1] !== '--depth');
  if (!names.length) die('usage: widgets:dto <Name|Outer.Name>… [--ts] [--depth N]');
  const seen = new Set<string>();
  const show = (name: string, level: number) => {
    if (seen.has(name)) return;
    seen.add(name);
    const d = findDto(REPO, name);
    if (!d) return console.log(`# ${name}: not found under backend/`);
    if (d.kind === 'enum') {
      console.log(asTs ? `export type ${name.split('.').pop()} = ${d.values!.map((v) => `'${v}'`).join(' | ')}; // ${d.where}` : `# enum ${name}  ${d.where}\n  ${d.values!.join(' | ')}`);
      return;
    }
    if (asTs) {
      console.log(`/** ${d.where} — primitives never null; others may be null */\nexport interface ${name.split('.').pop()} {`);
      for (const f of d.fields) {
        const prim = /^(int|long|double|float|short|boolean)$/.test(f.type);
        const req = prim || f.notes.some((n) => n === '@NotNull' || n === '@NotBlank');
        console.log(`  ${f.name}: ${tsType(f.type)}${req ? '' : ' | null'};`);
      }
      console.log('}');
    } else {
      console.log(`# ${d.kind} ${name}  ${d.where}`);
      for (const f of d.fields) console.log(`  ${f.name}: ${f.type}${f.notes.length ? `  ${f.notes.join(' ')}` : ''}`);
    }
    if (level < depth) {
      for (const f of d.fields) {
        const inner = /([A-Z]\w*)(?:>)?$/.exec(f.type.replace(/\[\]$/, ''))?.[1];
        if (inner && !JAVA_TS[inner] && !['List', 'Map', 'Set', 'Object'].includes(inner)) show(inner, level + 1);
      }
    }
  };
  for (const n of names) show(n, 1);
}

/* ------------------------------------------------------------------ plan */

function printOps(ops: StageOp[]): void {
  for (const o of ops) {
    const meta = o.status === 'REUSE' ? `${o.opId} perm=${o.permission || '—'} ${o.risk}` : 'NEW — fixtures only until stage 2';
    console.log(`  ${o.status.padEnd(5)} ${o.kind.padEnd(7)} ${o.name.padEnd(30)} ${o.method.padEnd(6)} ${o.path}  ${meta}`);
  }
}

function plan(id: string, args: string[], quiet = false): { state: StageState; problems: string[] } {
  const ops = scanCatalog(REPO);
  const { ops: derived, problems } = deriveOps(id, ops);
  const prev = load(id);
  const state: StageState = prev ?? { id, stage: 'ui', ops: [], createdAt: new Date().toISOString(), history: [] };
  const requirement = flag(args, '--requirement');
  if (requirement) state.requirement = requirement;
  const before = new Set(state.ops.map((o) => `${o.name} ${o.status}`));
  const after = new Set(derived.map((o) => `${o.name} ${o.status}`));
  const changed = before.size !== after.size || [...after].some((x) => !before.has(x));
  if (state.acceptance && state.stage !== 'ui') {
    const accepted = new Set(state.acceptance.newOps);
    const newNow = derived.filter((o) => o.status === 'NEW' && !accepted.has(o.name)).map((o) => o.name);
    if (newNow.length) problems.push(`operations added after UI approval: ${newNow.join(', ')} — the contract changed; show the user and re-run accept`);
  }
  state.ops = derived;
  if (!prev) event(state, 'planned', requirement);
  else if (changed) event(state, 'ops-changed', derived.map((o) => `${o.name}:${o.status}`).join(' '));
  save(state);
  if (!quiet) {
    console.log(`# ${id} — stage ${state.stage} · ${derived.filter((o) => o.status === 'REUSE').length} REUSE · ${derived.filter((o) => o.status === 'NEW').length} NEW`);
    printOps(derived);
    for (const p of problems) console.log(`  ✗ ${p}`);
  }
  return { state, problems };
}

/* ------------------------------------------------------------------ verify */

async function verify(id: string, args: string[]): Promise<boolean> {
  const dir = folder(id);
  const steps: { step: string; ok: boolean; note?: string; log?: string; out?: string }[] = [];

  // 1. operations + code rules (the same FORBIDDEN rules the workbench pack validator applies)
  const { state, problems } = plan(id, [], true);
  if (state.stage === 'done' && state.ops.some((o) => o.status === 'NEW')) problems.push('stage done but NEW operations remain');
  const srcFiles = files(dir).filter((f) => /\.(tsx?)$/.test(f) && !/\.test\.tsx?$/.test(f));
  const tests = files(dir).filter((f) => /\.test\.tsx?$/.test(f));
  for (const f of srcFiles) for (const i of codeRuleIssues(relative(dir, f), readFileSync(f, 'utf8'))) problems.push(`${i.file}: ${i.message}`);
  for (const f of srcFiles) {
    const code = readFileSync(f, 'utf8');
    if (/@media\b/.test(code)) problems.push(`${relative(dir, f)}: @media — use container tiers (context.tier / @container)`);
    if (/\b(?:text|bg|border)-(?:red|green|blue|yellow|orange|gray|slate|zinc|amber|emerald|rose)-\d{2,3}\b/.test(code)) problems.push(`${relative(dir, f)}: Tailwind palette colour — design tokens only (ui-std §2)`);
    if (/window\.confirm|\balert\s*\(/.test(code)) problems.push(`${relative(dir, f)}: window.confirm/alert — use InlineConfirm`);
    if (/useMutation\s*\(/.test(code) && !/retry:\s*0/.test(code)) problems.push(`${relative(dir, f)}: useMutation without retry: 0 (money/approvals are never auto-retried)`);
  }
  for (const req of ['index.tsx', 'widget.tsx']) if (!existsSync(join(dir, req))) problems.push(`${req} is missing`);
  if (state.ops.length && !existsSync(join(dir, 'data.ts')) && state.ops.some((o) => o.status === 'NEW')) problems.push('NEW operations need data.ts mappings + fixtures');
  if (!tests.length) problems.push('no <name>.test.ts in the widget folder — agent mode requires unit tests');
  steps.push({ step: 'operations + code rules', ok: problems.length === 0, out: problems.join('\n') });

  // 2. registry coherence
  const chk = await run(['bun', 'run', 'tools/widgets.ts', 'check']);
  steps.push({ step: 'widgets:check', ok: chk.ok, log: logTo(id, 'widgets-check', chk.out), out: chk.out.split('\n').filter((l) => l.includes(id) || l.includes('FAILED')).join('\n') || chk.out });

  // 3. TypeScript strict, scoped to the widget folder (project-wide: bun run typecheck)
  const tsDir = join(CACHE, id);
  mkdirSync(tsDir, { recursive: true });
  writeFileSync(join(tsDir, 'tsconfig.json'), JSON.stringify({
    extends: relative(tsDir, join(FRONTEND, 'tsconfig.json')),
    compilerOptions: { noEmit: true },
    // the e2e spec is Node/Playwright code (no @types/node in this repo) — Playwright transpiles it; eslint covers it
    include: [relative(tsDir, join(dir, '**', '*'))],
  }, null, 2));
  const tsc = await run(['bunx', '--bun', 'tsc', '--noEmit', '-p', join(tsDir, 'tsconfig.json')]);
  steps.push({ step: 'tsc --noEmit (widget scope)', ok: tsc.ok, log: logTo(id, 'tsc', tsc.out), out: tsc.out });

  // 4. ESLint, zero warnings (widget + its e2e spec)
  const spec = join(FRONTEND, 'tests', 'e2e', 'widgets', `${id}.spec.ts`);
  const lintTargets = [relative(FRONTEND, dir), ...(existsSync(spec) ? [relative(FRONTEND, spec)] : [])];
  const lint = await run(['bunx', '--bun', 'eslint', ...lintTargets, '--max-warnings', '0']);
  steps.push({ step: 'eslint', ok: lint.ok, log: logTo(id, 'eslint', lint.out), out: lint.out });

  // 5. unit tests
  if (tests.length && !args.includes('--no-tests')) {
    const vt = await run(['bunx', 'vitest', 'run', relative(FRONTEND, dir)]);
    const summary = vt.out.split('\n').filter((l) => /Tests\s+\d|Test Files|FAIL|✗|×/.test(l)).join('\n');
    steps.push({ step: 'vitest', ok: vt.ok, log: logTo(id, 'vitest', vt.out), out: vt.ok ? summary : vt.out });
  }

  // 6. the chat-LLM skill + endpoints.json reflect the code (stage 2 adds endpoints)
  const fresh = await run(['bun', 'run', 'tools/widget-pack/build-skill.ts', '--check']);
  const strict = state.stage !== 'ui';
  steps.push({ step: `widgets:skill fresh${strict ? '' : ' (warning only in stage 1)'}`, ok: fresh.ok || !strict, note: fresh.ok ? undefined : 'run: bun run widgets:skill', out: fresh.ok ? '' : fresh.out });

  const ok = steps.every((s) => s.ok);
  const hash = uiHash(id);
  state.verify = { at: new Date().toISOString(), ok, uiHash: hash, steps: steps.map((s) => ({ step: s.step, ok: s.ok })) };
  event(state, ok ? 'verify-ok' : 'verify-failed');
  save(state);

  console.log(`# verify ${id} — stage ${state.stage} · ui ${hash}`);
  for (const s of steps) {
    console.log(`  ${s.ok ? '✓' : '✗'} ${s.step}${s.note ? ` — ${s.note}` : ''}${!s.ok && s.log ? `  (log: ${s.log})` : ''}`);
    if (!s.ok && s.out) console.log(tail(s.out));
  }
  console.log(ok ? '  → all green' : '  → fix the ✗ steps and re-run');
  return ok;
}

/* ------------------------------------------------------------------ e2e */

const SPEC_TEMPLATE = (id: string, actions: { id: string; cell: string }[], requires: string[]) => `/**
 * ${id} — end-to-end spec (fsp-widget skill). Scaffolded by \`bun run widgets:e2e ${id} --scaffold\`;
 * extend the INTERACTIONS blocks with this widget's real user journeys (open, filter, act, confirm).
 *
 *   workbench lane — fixtures, every scenario × every tier, geometry, permission gating (stage 1)
 *   portal lane    — real BFF sign-in + the real screen; NEW operations answered from fixtures
 *                    until FSP_WIDGET_LIVE=1 (stage 2: nothing stubbed)
 *
 * Run: bun run widgets:e2e ${id} [--lane workbench|portal|all] [--live]
 */
import { expect, test } from '@playwright/test';
import {
  expectActionsWith, expectGatedWithoutCells, openScreen, openWorkbench, portalSession, stageOf, stubPlannedOps, sweepScenarios,
  type Scenario,
} from '../widget-support';
import { fixtures } from '../../../src/portals/shared/widgets/${id.split('.')[0]}/${id.split('.')[1]}/data';

const ID = '${id}';
const LANES = (process.env.FSP_WIDGET_LANES ?? 'workbench,portal').split(',');
/** scenarios: where the action can appear (conditional actions need a fixture state that shows them) */
const ACTIONS: { id: string; cell: string; scenarios?: Scenario[] }[] = ${JSON.stringify(actions)};
/** the widget's requires cells — an action needs the widget visible too */
const REQUIRES: string[] = ${JSON.stringify(requires)};

test.describe(\`\${ID} · workbench (fixtures)\`, () => {
  test.skip(!LANES.includes('workbench'), 'workbench lane not selected');

  test('every scenario renders its state at every tier, without layout faults', async ({ page }) => {
    await sweepScenarios(page, ID);
  });

  test('without its cells the widget is forbidden and no action is reachable', async ({ page }) => {
    await expectGatedWithoutCells(page, ID, ACTIONS.map((a) => a.id));
  });

  for (const a of ACTIONS) {
    test(\`action \${a.id} shows with \${a.cell}\`, async ({ page }) => {
      await expectActionsWith(page, ID, [...new Set([a.cell, ...REQUIRES])], [a.id], a.scenarios);
    });
  }

  test('INTERACTIONS — the main journey on fixtures', async ({ page }) => {
    await openWorkbench(page, ID, { tier: 'md' });
    const frame = page.locator('[data-tier="md"] [data-cell]');
    await expect(frame).toBeVisible();
    // drive the widget like a user here: open a row, fill a form, press an action, assert the result
  });
});

test.describe(\`\${ID} · portal (BFF session)\`, () => {
  test.skip(!LANES.includes('portal'), 'portal lane not selected');

  test('renders on its real screen for a real session', async ({ browser }) => {
    const stage = stageOf(ID);
    const s = await portalSession(browser, stage);
    const stubbed = await stubPlannedOps(s.page, stage, fixtures as Record<string, Partial<Record<string, unknown>>>);
    if (stubbed.length) console.log(\`stage-1 stubs (NEW ops from fixtures): \${stubbed.join(', ')}\`);
    await openScreen(s, stage);
    await expect(s.page.getByText(/not registered yet/i), 'widget registered with core').toHaveCount(0);
    // assert the widget's live content and journey here (same steps as the workbench INTERACTIONS)
    await s.context.close();
  });
});
`;

async function e2e(id: string, args: string[]): Promise<void> {
  const state = must(id);
  const spec = join(FRONTEND, 'tests', 'e2e', 'widgets', `${id}.spec.ts`);
  if (args.includes('--scaffold')) {
    if (existsSync(spec)) die(`${relative(FRONTEND, spec)} exists — edit it instead`);
    const m = manifest(id);
    mkdirSync(dirname(spec), { recursive: true });
    writeFileSync(spec, SPEC_TEMPLATE(id, m.actions.map((a) => ({ id: a.id, cell: a.cell })), m.requires));
    console.log(`wrote ${relative(FRONTEND, spec)} — fill in the INTERACTIONS blocks, then: bun run widgets:e2e ${id}`);
    return;
  }
  if (!existsSync(spec)) die(`no spec yet — run: bun run widgets:e2e ${id} --scaffold`);
  const lane = flag(args, '--lane') ?? (state.stage === 'ui' ? 'workbench' : 'all');
  const live = args.includes('--live');
  const lanes = lane === 'all' ? ['workbench', 'portal'] : [lane];
  if (!(await reachable(`${SPA}/widget-workbench/`))) die(`vite dev server not reachable at ${SPA} — start it: cd frontend && bun run dev (or bun run workbench)`);
  if (lanes.includes('portal') && !(await reachable(`${SPA}/bff/session`))) die('portal lane needs the BFF + gateway + core + idp up (make stack-up / make backend-*)');
  if (live && state.ops.some((o) => o.status === 'NEW')) die(`--live but NEW operations remain: ${state.ops.filter((o) => o.status === 'NEW').map((o) => o.name).join(', ')}`);

  // forward slashes: playwright reads the argument as a regex, and Windows' `\e`, `\w` … are escapes
  const r = await run(['bunx', '--bun', 'playwright', 'test', relative(FRONTEND, spec).replace(/\\/g, '/'), '--reporter=line'], {
    FSP_WIDGET_LANES: lanes.join(','), FSP_WIDGET_LIVE: live ? '1' : '0',
  });
  const log = logTo(id, `e2e-${lanes.join('-')}${live ? '-live' : ''}`, r.out);
  const hash = uiHash(id);
  const at = new Date().toISOString();
  state.e2e = state.e2e ?? {};
  if (lanes.includes('workbench')) state.e2e.workbench = { at, ok: r.ok, uiHash: hash };
  if (lanes.includes('portal')) state.e2e.portal = { at, ok: r.ok, uiHash: hash, live };
  event(state, r.ok ? 'e2e-ok' : 'e2e-failed', `${lanes.join('+')}${live ? ' live' : ''}`);
  save(state);
  const summary = r.out.split('\n').filter((l) => /passed|failed|skipped|flaky|✘|Error:/.test(l)).slice(-15).join('\n');
  console.log(`# e2e ${id} — ${lanes.join('+')}${live ? ' (live)' : ''}: ${r.ok ? '✓ green' : '✗ failed'}  (log: ${log})`);
  console.log(tail(r.ok ? summary : r.out, r.ok ? 8 : 40));
  if (!r.ok) process.exit(1);
}

/* ------------------------------------------------------------------ register */

async function register(id: string): Promise<void> {
  const state = must(id);
  if (!(await reachable(`${SPA}/bff/session`))) die(`BFF not reachable through ${SPA} — registration needs the stack (idp, gateway, bff, core) + vite`);
  const out = join(CACHE, id, 'registered.json');
  mkdirSync(dirname(out), { recursive: true });
  const r = await run(['bunx', '--bun', 'playwright', 'test', '-c', 'playwright.setup.config.ts', 'tests/setup/widget-register.spec.ts', '--reporter=line'], {
    FSP_WIDGET_ID: id, FSP_WIDGET_RESULT: out,
  });
  const log = logTo(id, 'register', r.out);
  if (!r.ok || !existsSync(out)) {
    console.log(`✗ register ${id} failed  (log: ${log})`);
    console.log(tail(r.out, 30));
    process.exit(1);
  }
  const res = JSON.parse(readFileSync(out, 'utf8')) as { version: string; by: string; at: string };
  state.registered = { at: res.at, version: res.version, by: res.by, uiHash: uiHash(id) };
  event(state, 'registered', `${id}@${res.version} by ${res.by}`);
  save(state);
  console.log(`✓ ${id}@${res.version} is in core's widget registry (PUT /api/ui/widgets as ${res.by}, BFF session)`);
}

/* ------------------------------------------------------------------ gates */

interface Gate { label: string; ok: boolean; fix: string }

function uiGates(id: string, s: StageState): Gate[] {
  const hash = uiHash(id);
  return [
    { label: 'verify green on the current files', ok: !!s.verify?.ok && s.verify.uiHash === hash, fix: `bun run widgets:verify ${id}` },
    { label: 'registered in core\'s widget registry', ok: !!s.registered, fix: `bun run widgets:register-bff ${id}` },
    { label: 'workbench e2e green on the current files', ok: !!s.e2e?.workbench?.ok && s.e2e.workbench.uiHash === hash, fix: `bun run widgets:e2e ${id} --lane workbench` },
  ];
}

function backendGates(id: string, s: StageState): Gate[] {
  const hash = uiHash(id);
  const left = s.ops.filter((o) => o.status === 'NEW').map((o) => o.name);
  const acc = s.acceptance;
  const weak = s.ops.filter((o) => o.status === 'REUSE' && acc?.newOps.includes(o.name) && !o.permission).map((o) => o.name);
  return [
    { label: 'every NEW operation now exists in core (@FspOperation) or a plugin manifest', ok: left.length === 0, fix: `implement: ${left.join(', ') || '—'} then bun run widgets:plan ${id}` },
    { label: 'new operations carry a permission key', ok: weak.length === 0, fix: `add permission = "…" to @FspOperation of ${weak.join(', ')}` },
    { label: 'UI unchanged since approval (or re-approved)', ok: !!acc && acc.uiHash === hash, fix: 'show the user the UI change and re-run widgets:accept' },
    { label: 'contract (data.ts/model.ts) unchanged since approval', ok: !!acc && acc.contractHash === contractHash(id), fix: 'a shape changed — show the user, then widgets:accept again' },
    { label: 'verify green on the current files', ok: !!s.verify?.ok && s.verify.uiHash === hash, fix: `bun run widgets:verify ${id}` },
    { label: 'portal e2e green LIVE (nothing stubbed)', ok: !!s.e2e?.portal?.ok && !!s.e2e.portal.live && s.e2e.portal.uiHash === hash, fix: `bun run widgets:e2e ${id} --lane all --live` },
  ];
}

function printGates(title: string, gates: Gate[]): boolean {
  console.log(title);
  for (const g of gates) console.log(`  ${g.ok ? '✓' : '✗'} ${g.label}${g.ok ? '' : `  → ${g.fix}`}`);
  return gates.every((g) => g.ok);
}

function stage(id: string): void {
  const s = must(id);
  plan(id, [], true);
  const fresh = must(id);
  console.log(`# ${id} — stage ${fresh.stage}${fresh.requirement ? `\n  requirement: ${fresh.requirement}` : ''}`);
  printOps(fresh.ops);
  if (s.acceptance) console.log(`  UI approved by ${s.acceptance.by} at ${s.acceptance.at}${s.acceptance.note ? ` — ${s.acceptance.note}` : ''}`);
  if (fresh.stage === 'ui') printGates('\n# gate to ask for UI approval', uiGates(id, fresh));
  if (fresh.stage === 'backend') printGates('\n# gate to finish (widgets:done)', backendGates(id, fresh));
  if (fresh.stage === 'done') console.log('\n  done — UI approved, backend live, e2e green.');
}

function accept(id: string, args: string[]): void {
  const by = flag(args, '--by');
  if (!by) die('usage: widgets:accept <id> --by "<who approved>" [--note "…"] — only after the user explicitly approved the UI');
  plan(id, [], true);
  const s = must(id);
  if (s.stage === 'done') die(`${id} is done — a new UI change starts with widgets:plan and a new approval`);
  if (!printGates(`# UI approval gate for ${id}`, uiGates(id, s))) die('gate not met — fix the ✗ items first');
  const newOps = s.ops.filter((o) => o.status === 'NEW').map((o) => o.name);
  s.acceptance = { by, at: new Date().toISOString(), note: flag(args, '--note'), uiHash: uiHash(id), contractHash: contractHash(id), newOps };
  s.stage = 'backend';
  event(s, 'ui-accepted', `${by}${newOps.length ? ` · NEW: ${newOps.join(', ')}` : ' · no NEW ops (integration only)'}`);
  save(s);
  console.log(`✓ UI approved by ${by} — ${id} is now in stage 2 (backend). Locked: ui ${s.acceptance.uiHash}, contract ${s.acceptance.contractHash}.`);
  if (newOps.length) console.log(`  build: ${newOps.map((n) => { const o = s.ops.find((x) => x.name === n)!; return `${o.method} ${o.path.replace(/^\/api\//, '/v1/')}`; }).join(' · ')}`);
}

async function done(id: string): Promise<void> {
  plan(id, [], true);
  const s = must(id);
  if (s.stage !== 'backend') die(`${id} is in stage ${s.stage}; done applies to stage backend`);
  const fresh = await run(['bun', 'run', 'tools/widget-pack/build-skill.ts', '--check']);
  const gates = [...backendGates(id, s), { label: 'generated chat skill + endpoints.json fresh', ok: fresh.ok, fix: 'bun run widgets:skill' }];
  if (!printGates(`# completion gate for ${id}`, gates)) die('gate not met — fix the ✗ items first');
  s.stage = 'done';
  event(s, 'done');
  save(s);
  console.log(`✓ ${id} done — UI approved by ${s.acceptance?.by}, backend live, e2e green.`);
}

/* ------------------------------------------------------------------ main */

async function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  const id = rest[0] ?? '';
  switch (cmd) {
    case 'survey': return survey(rest);
    case 'dto': return dto(rest);
    case 'plan': plan(id, rest.slice(1)); return;
    case 'verify': if (!(await verify(id, rest.slice(1)))) process.exit(1); return;
    case 'e2e': return e2e(id, rest.slice(1));
    case 'register': return register(id);
    case 'stage': return stage(id);
    case 'accept': return accept(id, rest.slice(1));
    case 'done': return done(id);
    default:
      console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0].replace(/^\/\*\*?/, '').replace(/^ \* ?/gm, ''));
      if (cmd) process.exit(1);
  }
}

await main();
