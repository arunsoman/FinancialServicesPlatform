/**
 * fsp-widget-pack — DEV-ONLY Vite plugin behind the workbench's "New widget" page
 * (docs/skills/fsp-widget-author/SKILL.md). Never part of a production build (`apply: 'serve'`),
 * and it only answers loopback callers from the dev origin with the X-FSP-Widget-Pack header
 * (a cross-site page cannot set it without a CORS preflight, which this endpoint never grants).
 *
 *   GET  /__fsp/widget-pack/skill    → the generated SKILL.md (copy button)
 *   POST /__fsp/widget-pack/stage    { text }        → parse + write to widgets/.pack-staging/<name>
 *                                                      + load + tsc + eslint + vitest; answers checks
 *                                                      and the core registration payload
 *   POST /__fsp/widget-pack/commit   { id, sha }     → move the staged folder to widgets/<area>/<name>
 *   POST /__fsp/widget-pack/discard  { id }          → drop the staged folder
 *
 * Staging lives in a dot-folder so the registry's import.meta.glob never sees half-checked
 * code (no HMR churn); the browser registers with core (its own BFF session) BETWEEN stage
 * and commit, then commits — after which Vite picks the widget up and the preview opens.
 * Overwriting is allowed only for folders an earlier pack created (.widget-pack.json marker).
 */
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Plugin, ViteDevServer } from 'vite';
import { checkDataModule, parsePack } from '../../src/widget-workbench/pack/pack';
import type { Endpoint, PackIssue, WidgetPack } from '../../src/widget-workbench/pack/pack';
import { endpointsOf, scanCatalog } from './catalog';

interface Check {
  step: string;
  ok: boolean;
  output: string;
}

interface Staged {
  pack: WidgetPack;
  sha: string;
  dir: string;
}

const MARKER = '.widget-pack.json';
const MAX_BODY = 400_000;

export function widgetPackPlugin(): Plugin {
  let server: ViteDevServer;
  let root = '';
  const staged = new Map<string, Staged>();
  let busy = false;

  const widgetsDir = () => join(root, 'src', 'portals', 'shared', 'widgets');
  const stagingDir = (name: string) => join(widgetsDir(), '.pack-staging', name);
  const finalDir = (p: WidgetPack) => join(widgetsDir(), p.area, p.name);

  return {
    name: 'fsp-widget-pack',
    apply: 'serve',
    config() {
      return {
        // staged (unchecked) code must never trigger HMR/page reloads in open workbench tabs
        server: { watch: { ignored: ['**/.pack-staging/**'] } },
        // pre-bundle every library a pack may import, so a first use never forces a dep re-optimize reload
        optimizeDeps: { include: ['react', 'clsx', 'zod', 'date-fns', 'lucide-react', '@tanstack/react-query'] },
      };
    },
    configResolved(c) {
      root = c.root;
    },
    configureServer(s) {
      server = s;
      s.middlewares.use('/__fsp/widget-pack', (req, res) => {
        void handle(req, res).catch((e: unknown) => send(res, 500, { error: e instanceof Error ? e.message : String(e) }));
      });
    },
  };

  async function handle(req: IncomingMessage, res: ServerResponse) {
    if (!trusted(req)) return send(res, 403, { error: 'widget-pack: loopback dev origin + X-FSP-Widget-Pack header only' });
    const path = (req.url ?? '').split('?')[0];
    if (req.method === 'GET' && path === '/skill') {
      const f = join(root, '..', 'docs', 'skills', 'fsp-widget-author', 'SKILL.md');
      if (!existsSync(f)) return send(res, 404, { error: 'SKILL.md not generated — run bun run widgets:skill' });
      res.setHeader('content-type', 'text/markdown; charset=utf-8');
      return res.end(readFileSync(f, 'utf8'));
    }
    if (req.method !== 'POST') return send(res, 405, { error: 'POST only' });
    const body = await readBody(req);
    if (busy) return send(res, 409, { error: 'Another pack is being checked — try again in a moment.' });
    busy = true;
    try {
      if (path === '/stage') return send(res, 200, await stage(String(body.text ?? '')));
      if (path === '/commit') return send(res, 200, commit(String(body.id ?? ''), String(body.sha ?? '')));
      if (path === '/discard') {
        discard(String(body.id ?? ''));
        return send(res, 200, { ok: true });
      }
      return send(res, 404, { error: 'unknown widget-pack action' });
    } finally {
      busy = false;
    }
  }

  /** Loopback socket + dev-server origin + custom header (forces a preflight cross-site). */
  function trusted(req: IncomingMessage): boolean {
    const ip = req.socket.remoteAddress ?? '';
    const loopback = ip === '127.0.0.1' || ip === '::1' || ip === '::ffff:127.0.0.1';
    const origin = req.headers.origin;
    const port = server.config.server.port ?? 5173;
    const okOrigin = origin === undefined
      || [`http://localhost:${port}`, `http://127.0.0.1:${port}`, `http://[::1]:${port}`].includes(origin);
    return loopback && okOrigin && req.headers['x-fsp-widget-pack'] === '1';
  }

  async function stage(text: string) {
    const parsed = parsePack(text);
    if (!parsed.ok) return { ok: false, issues: parsed.issues, checks: [] as Check[] };
    const pack = parsed.pack;
    const target = finalDir(pack);
    if (existsSync(target) && !existsSync(join(target, MARKER))) {
      return {
        ok: false, checks: [] as Check[],
        issues: [{ message: `widgets/${pack.area}/${pack.name} already exists and was not created by a pack — pick another id.` }],
      };
    }
    const sha = createHash('sha256').update(text).digest('hex');
    const dir = stagingDir(pack.name);
    discard(pack.id);
    mkdirSync(dir, { recursive: true });
    for (const f of pack.files) writeFileSync(join(dir, f.name), f.content);
    writeFileSync(join(dir, MARKER), JSON.stringify({ id: pack.id, label: pack.label, summary: pack.summary, sha, stagedAt: new Date().toISOString() }, null, 2) + '\n');
    staged.set(pack.id, { pack, sha, dir });

    const checks: Check[] = [];
    const issues: PackIssue[] = [];
    const rel = relative(root, dir);

    // 1. load the modules (catches runtime manifest errors) + data/endpoint rules
    let registration: unknown = null;
    // the staging folder is unwatched, so drop any SSR copies from an earlier check of this pack
    for (const [file, mods] of server.moduleGraph.fileToModulesMap) {
      if (file.startsWith(dir)) for (const m of mods) server.moduleGraph.invalidateModule(m);
    }
    try {
      // the code-derived scan (same one widgets:skill and the agent tools use) — never a stale copy
      const endpoints: Endpoint[] = endpointsOf(scanCatalog(join(root, '..')));
      const data = (await server.ssrLoadModule(`/${rel}/data.ts`)) as Parameters<typeof checkDataModule>[1];
      issues.push(...checkDataModule(pack.id, data, endpoints));
      const idx = (await server.ssrLoadModule(`/${rel}/index.tsx`)) as { default?: { id?: string; sources?: string[]; commands?: string[] } };
      const def = idx.default;
      const core = (await server.ssrLoadModule('/src/portals/shared/widgets/core/sources.ts')) as {
        resolveSource: (n: string, p: string) => unknown;
        resolveCommand: (n: string, p: string) => unknown;
      };
      if (!def || def.id !== pack.id) issues.push({ file: 'index.tsx', message: `default export must be the widget with id '${pack.id}'.` });
      for (const s of def?.sources ?? []) {
        if (!(data.sources ?? {})[s] && !core.resolveSource(s, 'admin')) issues.push({ file: 'index.tsx', message: `source "${s}" is not defined in data.ts.` });
      }
      for (const c of def?.commands ?? []) {
        if (!(data.commands ?? {})[c] && !core.resolveCommand(c, 'admin')) issues.push({ file: 'index.tsx', message: `command "${c}" is not defined in data.ts.` });
      }
      const model = (await server.ssrLoadModule('/src/widget-workbench/model.ts')) as { registration: (d: unknown) => unknown };
      registration = def ? model.registration(def) : null;
      checks.push({ step: 'Load manifest + data mapping', ok: issues.length === 0, output: issues.map((i) => `${i.file ?? ''} ${i.message}`.trim()).join('\n') });
    } catch (e) {
      checks.push({ step: 'Load manifest + data mapping', ok: false, output: e instanceof Error ? e.message : String(e) });
    }

    // 2. TypeScript strict, only this widget (+ what it imports)
    const tsDir = join(root, 'node_modules', '.cache', 'fsp-widget-pack');
    mkdirSync(tsDir, { recursive: true });
    const tsconfig = join(tsDir, 'tsconfig.json');
    writeFileSync(tsconfig, JSON.stringify({
      extends: relative(tsDir, join(root, 'tsconfig.json')),
      compilerOptions: { noEmit: true },
      include: [relative(tsDir, join(dir, '**', '*'))],
    }, null, 2));
    checks.push(await run('TypeScript (strict)', ['bunx', '--bun', 'tsc', '--noEmit', '-p', tsconfig]));

    // 3. ESLint, zero warnings
    checks.push(await run('ESLint', ['bunx', '--bun', 'eslint', rel, '--max-warnings', '0']));

    // 4. the pack's own tests, when it ships any
    if (pack.files.some((f) => f.name.endsWith('.test.ts'))) {
      checks.push(await run('Vitest', ['bunx', '--bun', 'vitest', 'run', '--dir', rel]));
    }

    const ok = checks.every((c) => c.ok);
    if (!ok) discard(pack.id);
    server.config.logger.info(`widget-pack stage id=${pack.id} ok=${ok}`, { timestamp: true });
    return { ok, id: pack.id, area: pack.area, name: pack.name, sha, files: pack.files.map((f) => f.name), checks, issues, registration };
  }

  function commit(id: string, sha: string) {
    const s = staged.get(id);
    if (!s || s.sha !== sha || !existsSync(s.dir)) return { ok: false, error: 'Nothing staged for this pack — check it again.' };
    const target = finalDir(s.pack);
    if (existsSync(target)) {
      if (!existsSync(join(target, MARKER))) return { ok: false, error: 'Target folder is hand-written — refusing to overwrite.' };
      const backup = join(tmpdir(), `fsp-widget-pack-${id}-${Date.now()}`);
      cpSync(target, backup, { recursive: true });
      rmSync(target, { recursive: true, force: true });
      server.config.logger.info(`widget-pack previous ${id} backed up to ${backup}`, { timestamp: true });
    }
    mkdirSync(join(widgetsDir(), s.pack.area), { recursive: true });
    renameSync(s.dir, target);
    staged.delete(id);
    server.config.logger.info(`widget-pack committed id=${id} → ${relative(root, target)}`, { timestamp: true });
    return { ok: true, path: relative(root, target) };
  }

  function discard(id: string) {
    const s = staged.get(id);
    const name = s?.pack.name ?? id.split('.')[1];
    if (name) rmSync(stagingDir(name), { recursive: true, force: true });
    staged.delete(id);
  }

  function run(step: string, cmd: string[]): Promise<Check> {
    return new Promise((resolve) => {
      const child = spawn(cmd[0], cmd.slice(1), { cwd: root, env: process.env });
      let out = '';
      const collect = (b: Buffer) => {
        out += b.toString();
      };
      child.stdout.on('data', collect);
      child.stderr.on('data', collect);
      const timer = setTimeout(() => child.kill('SIGKILL'), 180_000);
      child.on('close', (code) => {
        clearTimeout(timer);
        resolve({ step, ok: code === 0, output: trim(out) });
      });
      child.on('error', (e) => {
        clearTimeout(timer);
        resolve({ step, ok: false, output: e.message });
      });
    });
  }
}

function trim(s: string): string {
  const clean = s.replace(new RegExp(`${String.fromCharCode(27)}\\[[0-9;]*m`, 'g'), '').trim();
  return clean.length > 6000 ? `${clean.slice(0, 6000)}\n…(truncated)` : clean;
}

function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new Error('pack too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}') as Record<string, unknown>);
      } catch {
        reject(new Error('body must be JSON'));
      }
    });
    req.on('error', reject);
  });
}

function send(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status;
  res.setHeader('content-type', 'application/json');
  res.setHeader('cache-control', 'no-store');
  res.end(JSON.stringify(body));
}
