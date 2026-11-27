/**
 * Widgets tooling — FSP widget harness CLI (plans/widget-harness-prd.md §5.C).
 * Run with bun:
 *   bun run tools/widgets.ts check        — registry coherence (decision 6)
 *   bun run tools/widgets.ts list         — registry + operations grid
 *   bun run tools/widgets.ts gen <id>     — scaffold a new widget (index.tsx)
 *   bun run tools/widgets.ts register <id> — register widget with backend (PUT /v1/ui/widgets)
 *
 * Bun executes TS directly — the registry loads as plain modules.
 */
import { readdirSync, readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const WIDGETS_DIR = join(ROOT, 'src', 'portals', 'shared', 'widgets');
const SOURCES_FILE = join(WIDGETS_DIR, 'core', 'sources.ts');
const CATALOGUE_FILE = join(WIDGETS_DIR, 'permission-catalogue.ts');

const CATA = readFileSync(CATALOGUE_FILE, 'utf8');
const KEYS = [...CATA.matchAll(/'([a-z][a-z0-9._]*)'/g)].map((m) => m[1]);

function widgetFiles(dir: string): string[] {
  const out: string[] = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (!e.isDirectory() || e.name === 'core') continue;
    const sub = join(dir, e.name);
    for (const s of readdirSync(sub, { withFileTypes: true })) {
      if (s.isDirectory() && s.name === 'core') continue;
      if (s.isDirectory()) {
        const idx = join(sub, s.name, 'index.tsx');
        try {
          readFileSync(idx);
          out.push(idx);
        } catch {
          /* no index — not a widget folder */
        }
      }
    }
  }
  return out;
}

/** Load widget manifests by reading index.tsx files as text (avoids Vite-only import.meta.glob in registry.ts). */
function loadRegistry(): Array<Record<string, unknown>> {
  const out: Array<Record<string, unknown>> = [];
  for (const f of widgetFiles(WIDGETS_DIR)) {
    const text = readFileSync(f, 'utf8');
    const idMatch = text.match(/id:\s*["']([^"']+)["']/);
    if (!idMatch) continue;

    const def: Record<string, unknown> = { id: idMatch[1] };

    const vMatch = text.match(/version:\s*["']([^"']+)["']/);
    if (vMatch) def.version = vMatch[1];

    const lMatch = text.match(/label:\s*["']([^"']+)["']/);
    if (lMatch) def.label = lMatch[1];

    const cMatch = text.match(/category:\s*["']([^"']+)["']/);
    if (cMatch) def.category = cMatch[1];

    const reqMatch = text.match(/requires:\s*\[([^\]]*)\]/);
    if (reqMatch) {
      def.requires = reqMatch[1].split(',').map((s) => s.trim().replace(/["']/g, '')).filter(Boolean);
    }

    const srcMatch = text.match(/sources:\s*\[([^\]]*)\]/);
    if (srcMatch) {
      def.sources = srcMatch[1].split(',').map((s) => s.trim().replace(/["']/g, '')).filter(Boolean);
    }

    const cmdMatch = text.match(/commands:\s*\[([^\]]*)\]/);
    if (cmdMatch) {
      def.commands = cmdMatch[1].split(',').map((s) => s.trim().replace(/["']/g, '')).filter(Boolean);
    }

    const coMatch = text.match(/commandOperations:\s*\{([^}]*)\}/);
    if (coMatch) {
      const ops: Record<string, string> = {};
      for (const pair of coMatch[1].split(',')) {
        const kv = pair.split(':').map((s) => s.trim().replace(/["']/g, ''));
        if (kv[0] && kv[1]) ops[kv[0]] = kv[1];
      }
      def.commandOperations = ops;
    }

    const sizeMatch = text.match(/size:\s*\{([^}]*)\}/);
    if (sizeMatch) {
      const size: Record<string, unknown> = {};
      for (const tier of ['xs', 'sm', 'md', 'lg']) {
        const tMatch = sizeMatch[1].match(new RegExp(tier + ':\\s*span\\.' + tier + '([^)]*)'));
        if (tMatch) {
          const args = tMatch[1].split(',').map((s) => s.trim());
          size[tier] = { min: parseInt(args[0], 10) || 1, default: parseInt(args[1], 10) || 1, max: parseInt(args[2], 10) || 1 };
        }
      }
      def.size = size;
    }

    const actMatch = text.match(/actions:\s*\[([\s\S]*?)\]\s*,?\s*commandOperations/);
    if (actMatch) {
      const actions: Array<Record<string, unknown>> = [];
      const block = actMatch[1];
      for (const m of block.matchAll(/\{([^}]*)\}/g)) {
        const fields: Record<string, unknown> = {};
        for (const kv of m[1].split(',')) {
          const parts = kv.split(':');
          if (parts[0] && parts.length > 1) fields[parts[0].trim()] = parts.slice(1).join(':').trim().replace(/["']/g, '');
        }
        if (fields.id || fields.label) actions.push(fields);
      }
      def.actions = actions;
    }

    out.push(def);
  }
  return out;
}

function parseWidgetId(id: string): { area: string; name: string } {
  const parts = id.split('.');
  if (parts.length < 2 || parts.some((p) => !/^[a-z][a-z0-9-]*$/.test(p))) {
    throw new Error(`Invalid widget id "${id}": must be "<area>.<name>" with lower-case dash-separated tokens`);
  }
  return { area: parts[0], name: parts.slice(1).join('.') };
}

/** --------------------------------------------------------------- gen */
function genWidget(id: string, opts: { label?: string; category?: string } = {}) {
  const { area, name } = parseWidgetId(id);
  const label = opts.label || name.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  const category = opts.category || 'General';
  const dir = join(WIDGETS_DIR, area, name);
  const idx = join(dir, 'index.tsx');

  if (existsSync(idx)) {
    throw new Error(`Widget "${id}" already exists at ${idx}`);
  }
  mkdirSync(dir, { recursive: true });

  const bundle = `widgets/${area}/${name}`;
  const requires = ['ui.widget.register'];
  const sources: string[] = [];
  const commands: string[] = [];
  const actions: Array<{ id: string; label: string; cell: string; command: string; kind?: string }> = [];
  const commandOperations: Record<string, string> = {};
  const CompName = name.charAt(0).toUpperCase() + name.slice(1).replace(/[-](.)/g, (m: string, c: string) => c.toUpperCase());

  const content = `/**
 * ${label} — generated by widgets:gen. Implement your widget here.
 * See the fsp-widget skill and plans/widget-harness-prd.md §5.B.
 */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { useWidgetRuntime, usePortData } from '../../core/runtime';
import { Panel, EmptyState } from '../../../ui/atoms';

function ${CompName}() {
  const { port } = useWidgetRuntime();
  const data = usePortData<unknown>(() => port.request('${sources[0] || 'overview.self' }'), []);

  if (data.state === 'loading') return <Panel className="p-4"><EmptyState title="Loading…" /></Panel>;
  if (data.state === 'forbidden') return <Panel className="p-4"><EmptyState title="Not allowed" hint="You lack a required permission cell." /></Panel>;
  if (data.state === 'empty') return <Panel className="p-4"><EmptyState title="No data" /></Panel>;
  if (data.state === 'error') return <Panel className="p-4"><EmptyState title="Error" hint={String(data.problem?.detail)} /></Panel>;

  return (
    <Panel className="p-4">
      <h2 className="text-sm font-semibold">{String(data.data ?? '—')}</h2>
    </Panel>
  );
}

const ${CompName}Widget = defineWidget({
  id: '${id}',
  version: '1.0.0',
  label: '${label}',
  description: 'Replace me with a real description.',
  category: '${category}',
  contexts: ['ANY'],
  requires: ${JSON.stringify(requires)},
  sources: ${JSON.stringify(sources)},
  commands: ${JSON.stringify(commands)},
  actions: ${JSON.stringify(actions, null, 2)},
  commandOperations: ${JSON.stringify(commandOperations, null, 2)},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6), lg: span.lg(4) },
  render: ${CompName},
});

export default ${CompName}Widget;
`;
  writeFileSync(idx, content);
  console.log(`OK Generated ${idx}`);
  console.log(`  id: ${id}`);
  console.log(`  label: ${label}`);
  console.log(`  category: ${category}`);
  console.log(`  bundle: ${bundle}`);
  console.log('');
  console.log('Next steps:');
  console.log('  1. Edit the component and manifest as needed.');
  console.log('  2. Add any new sources/commands to src/portals/shared/widgets/core/sources.ts');
  console.log('  3. Add any new permission cells to src/portals/shared/widgets/permission-catalogue.ts');
  console.log('  4. Run: bun run tools/widgets.ts check');
  console.log(`  5. Run: bun run tools/widgets.ts register ${id}`);
}

/** --------------------------------------------------------------- register */
async function registerWidget(id: string, token?: string) {
  // AGENTS §1: the gateway is the sole public origin and tokens never leave BFF/gateway — this
  // legacy path PUTs straight to core (:8081) with an optional bearer. Use the BFF-session path.
  console.warn(`! widgets:register talks to core directly (not FAPI 2.0). Prefer: bun run widgets:register-bff ${id}`);
  const { area, name } = parseWidgetId(id);
  const idx = join(WIDGETS_DIR, area, name, 'index.tsx');
  if (!existsSync(idx)) {
    throw new Error(`Widget "${id}" not found at ${idx}. Run "gen" first.`);
  }

  // Load the widget manifest by reading index.tsx as text (avoids Vite-only import.meta.glob).
  let text: string;
  try {
    text = readFileSync(idx, 'utf8');
  } catch (e) {
    throw new Error('Failed to read widget manifest from ' + idx + ': ' + e);
  }
  const idMatch = text.match(/id:\s*["']([^"']+)["']/);
  const verMatch = text.match(/version:\s*["']([^"']+)["']/);
  const lblMatch = text.match(/label:\s*["']([^"']+)["']/);
  const catMatch = text.match(/category:\s*["']([^"']+)["']/);
  const portalsMatch = text.match(/portals:\s*\[([^\]]*)\]/);
  const requiresMatch = text.match(/requires:\s*\[([^\]]*)\]/);
  const def: Record<string, unknown> = {};
  if (idMatch) def.id = idMatch[1];
  if (verMatch) def.version = verMatch[1];
  if (lblMatch) def.label = lblMatch[1];
  if (catMatch) def.category = catMatch[1];
  if (portalsMatch) def.portals = portalsMatch[1].split(',').map((s) => s.trim().replace(/["']/g, '')).filter(Boolean);
  if (requiresMatch) def.requires = requiresMatch[1].split(',').map((s) => s.trim().replace(/["']/g, '')).filter(Boolean);
  if (!def.id) {
    throw new Error('Widget manifest in ' + idx + ' has no id');
  }

  const body: Record<string, unknown> = {
    id: def.id,
    version: def.version ?? '1.0.0',
    label: def.label,
    category: def.category,
    bundle: `widgets/${area}/${name}`,
    portals: def.portals ?? ['ANY'],
    requires: def.requires ?? [],
  };
  if (def.bundleSha256) body['bundleSha256'] = def.bundleSha256;

  const base = process.env.FSP_BACKEND_URL ?? 'http://localhost:8081';
  const url = `${base}/v1/ui/widgets`;

  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  let res: Response;
  try {
    res = await fetch(url, {
      method: 'PUT',
      headers,
      body: JSON.stringify(body),
    });
  } catch (e) {
    throw new Error(`Request to ${url} failed: ${e}`);
  }

  const bodyText = await res.text();
  if (!res.ok) {
    console.error(`✗ Register failed (${res.status}): ${bodyText}`);
    process.exit(1);
  }

  console.log(`OK Registered widget "${id}" via PUT ${url}`);
  console.log(text);
}

/** --------------------------------------------------------------- main */
async function main() {
  const cmd = process.argv[2] ?? 'check';
  const args = process.argv.slice(3);

  if (cmd === 'check' || cmd === 'list') {
    const defs = loadRegistry();
    if (defs.length === 0) {
      console.error(`widgets:${cmd} — registry EMPTY (no widget index.tsx found)`);
      process.exit(1);
    }
    if (cmd === 'list') {
      for (const d of defs) {
        const ops = Object.values((d['commandOperations'] as Record<string, string>) ?? {});
        console.log(`${d['id']} v${d['version']} — ${d['label']} · ops: ${['view', ...ops].join(',')}`);
        for (const a of (d['actions'] as Array<Record<string, unknown>>) ?? []) {
          console.log(`   ${String(a['id'])}: ${String(a['label'])} [${String(a['cell'])}] → ${String(a['command'] ?? '—')}`);
        }
      }
      return;
    }
    // ---------- check ----------
    const errs: string[] = [];
    for (const d of defs) {
      const id = String(d['id']);
      const size = d['size'] as Record<string, unknown> | undefined;
      if (!size || !['xs', 'sm', 'md', 'lg'].every((t) => size[t])) errs.push(`${id}: size missing a tier`);
      const reqs = d['requires'] as unknown;
      const rows = Array.isArray(reqs) ? (reqs as string[]) : (reqs as { all: string[] } | null)?.all ?? [];
      // A widget that reads nothing and renders no actions (e.g. inventory.placeholder) legitimately
      // needs no cell; anything with a source/command/action must gate on at least one.
      const doesAnything =
        (Array.isArray(d['sources']) && (d['sources'] as string[]).length > 0) ||
        (Array.isArray(d['commands']) && (d['commands'] as string[]).length > 0) ||
        ((d['actions'] as unknown[] | undefined)?.length ?? 0) > 0;
      if (!rows.length && doesAnything) errs.push(`${id}: empty requires`);
      for (const r of rows) {
        if (!KEYS.includes(r)) errs.push(`${id}: requires "${r}" is not an FSP permission key`);
      }
      const cmdOps = (d['commandOperations'] as Record<string, string>) ?? {};
      for (const [c, op] of Object.entries(cmdOps)) {
        if (!['view', 'create', 'modify', 'approve'].includes(op)) errs.push(`${id}: commandOperations[${c}] — unknown op "${op}"`);
      }
      for (const a of (d['actions'] as Array<Record<string, unknown>>) ?? []) {
        if (a['command'] && !cmdOps[String(a['command'])]) errs.push(`${id}: action ${String(a['id'])} — command missing in commandOperations`);
        const cell = String(a['cell'] ?? '');
        if (!KEYS.includes(cell)) errs.push(`${id}: action ${String(a['id'])} — cell "${cell}" is not an FSP permission key (decision 6)`);
      }
    }
    // core/sources.ts + every widget folder's data.ts (per-widget mappings, SKILL.md §5)
    const dataFiles = widgetFiles(WIDGETS_DIR).map((f) => join(dirname(f), 'data.ts')).filter((f) => existsSync(f));
    const srcs = [SOURCES_FILE, ...dataFiles].map((f) => readFileSync(f, 'utf8')).join('\n');
    const mapped = new Set([...srcs.matchAll(/^\s*'([a-z][a-z0-9.]*)':/gm)].map((m) => m[1]));
    for (const d of defs) {
      const list = d['sources'];
      for (const s of Array.isArray(list) ? (list as string[]) : []) {
        if (!mapped.has(s)) errs.push(`${d['id']}: source "${s}" has no mapping in sources.ts or its data.ts (decision 2 — one network path)`);
      }
    }
    const uniq = new Set(defs.map((d) => String(d['id'])));
    if (uniq.size !== defs.length) errs.push('duplicate widget ids in the registry');
    if (errs.length) {
      for (const e of errs) console.error(`✗ ${e}`);
      console.error(`widgets:check FAILED — ${errs.length} error(s)`);
      process.exit(1);
    }
    console.log(`widgets:check OK — ${defs.length} widget(s), ${KEYS.length} permission keys, coherent (decision 6)`);
    return;
  }

  if (cmd === 'gen') {
    const widgetId = args[0];
    if (!widgetId) {
      console.error('Usage: bun run tools/widgets.ts gen <area>.<name> [--label "Label"] [--category "Cat"]');
      process.exit(1);
    }
    let label: string | undefined;
    let category: string | undefined;
    for (let i = 1; i < args.length; i++) {
      if (args[i] === '--label' && args[i + 1]) label = args[++i];
      else if (args[i] === '--category' && args[i + 1]) category = args[++i];
    }
    genWidget(widgetId, { label, category });
    return;
  }

  if (cmd === 'register') {
    const widgetId = args[0];
    if (!widgetId) {
      console.error('Usage: bun run tools/widgets.ts register <area>.<name> [--token <jwt>]');
      console.error('  Set FSP_BACKEND_URL to override http://localhost:8081');
      process.exit(1);
    }
    let token: string | undefined;
    for (let i = 1; i < args.length; i++) {
      if (args[i] === '--token' && args[i + 1]) token = args[++i];
    }
    await registerWidget(widgetId, token);
    return;
  }

  console.error(`Unknown command: ${cmd}`);
  console.error('Usage: bun run tools/widgets.ts [check|list|gen|register]');
  process.exit(1);
}

await main();
