#!/usr/bin/env node
/**
 * FSP living-architecture refresh — regenerates docs/generated/* from source.
 *
 *   node docs/gen/refresh.cjs            regenerate (never edits prose in architecture.md)
 *   node docs/gen/refresh.cjs --check    exit 1 if generated artifacts are stale (for CI)
 *
 * What it derives, and from what:
 *   fingerprint.md   — SHA-256 per tracked source tree (backend service dirs, frontend
 *                      src, migrations, compose) so any structural change is visible.
 *   dependencies.md  — Mermaid Gradle project dependency graph, parsed from
 *                      every service build.gradle.kts "project(" dependency line.
 *   operations.md    — table of every @FspOperation(id, permission, risk) in core,
 *                      greped from source (the contract the gateway routes on).
 *   migrations.md    — Flyway migration ledger from core db/migration filenames.
 *
 * Prose sections of docs/architecture.md (§1–§13, §15) are hand-maintained and are
 * NEVER touched by this script — see docs/architecture.md §14 for the workflow:
 * generators cover the "what changed" surfaces; a structural change runs this
 * script (pre-commit hook does it automatically) and the author updates only the
 * prose the diff actually touches.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..', '..');
const GEN = path.join(ROOT, 'docs', 'generated');
fs.mkdirSync(GEN, { recursive: true });
const check = process.argv.includes('--check');

// ---------------------------------------------------------------- helpers
function walk(dir, exts, acc = []) {
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return acc;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, exts, acc);
    else if (exts.some((x) => e.name.endsWith(x))) acc.push(p);
  }
  return acc;
}
const rel = (p) => path.relative(ROOT, p).split(path.sep).join('/');
const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex').slice(0, 12);

function writeIfChanged(name, content) {
  const file = path.join(GEN, name);
  const old = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
  if (old === content) return false;
  if (check) {
    console.error(`STALE docs/generated/${name} — run: node docs/gen/refresh.cjs`);
    process.exitCode = 1;
    return false;
  }
  fs.writeFileSync(file, content);
  console.log(`refreshed docs/generated/${name}`);
  return true;
}

// ---------------------------------------------------------------- fingerprint
const TREES = {
  'backend/core': ['backend/core/src'],
  'backend/idp': ['backend/idp/src'],
  'backend/gateway': ['backend/gateway/src'],
  'backend/bff': ['backend/bff/src'],
  'backend/shared': ['backend/shared/src'],
  'kyc plugin': ['backend/plugins/kyc/src'],
  'db migrations': ['backend/core/src/main/resources/db/migration', 'backend/core/src/main/resources/db/seed'],
  'frontend': ['frontend/src'],
  'infra': ['infrastructure/docker-compose.yml', 'infrastructure/pki/dev-pki.sh'],
};
let fp = ['# Auto-generated — fingerprint of each tracked source tree (SHA-256/12).', '',
  '| Tree | Files | Fingerprint |', '|---|---:|---|'];
for (const [label, dirs] of Object.entries(TREES)) {
  const files = dirs.flatMap((d) => {
    const p = path.join(ROOT, d);
    if (fs.existsSync(p) && fs.statSync(p).isFile()) return [p]; // tree entry may be a single file
    return walk(p, ['.java', '.ts', '.tsx', '.sql', '.yml', '.sh']);
  }).sort();
  const hash = crypto.createHash('sha256').update(files.map(sha).join('')).digest('hex').slice(0, 12);
  fp.push(`| ${label} | ${files.length} | \`${hash}\` |`);
}
writeIfChanged('fingerprint.md', fp.join('\n') + '\n');

// ---------------------------------------------------------------- gradle deps
const projects = ['core', 'idp', 'gateway', 'bff', 'shared', 'plugins/kyc'];
let dep = ['# Auto-generated — Gradle project dependencies (parsed from build.gradle.kts).', '',
  '```mermaid', 'flowchart LR'];
for (const p of projects) {
  const g = path.join(ROOT, 'backend', p, 'build.gradle.kts');
  if (!fs.existsSync(g)) continue;
  const txt = fs.readFileSync(g, 'utf8');
  const id = p === 'shared' ? 'shared' : p.replace('plugins/', 'p-');
  dep.push(`    ${id}["${p} ${fs.existsSync(path.join(ROOT, 'backend', p, 'src')) ? '' : '(build files only)'}"]`);
  [...txt.matchAll(/(?:implementation|compileOnly|api)\s*\(?\s*project\(":([a-zA-Z0-9/-]+)"\)/g)]
    .forEach((m) => dep.push(`    ${id} --> ${m[1].replace('plugins/', 'p-')}`));
}
dep.push('```');
writeIfChanged('dependencies.md', dep.join('\n') + '\n');

// ---------------------------------------------------------------- operations
let ops = ['# Auto-generated — every @FspOperation declared in core (routed only if listed here).', '',
  '| Operation id | Permission | Risk | Source |', '|---|---|---|---|'];
const seen = new Set();
for (const f of walk(path.join(ROOT, 'backend/core/src/main'), ['.java'])) {
  const txt = fs.readFileSync(f, 'utf8');
  const re = /@FspOperation\(\s*id\s*=\s*"([^"]+)"(?:\s*,\s*permission\s*=\s*"([^"]*)")?(?:\s*,\s*component\s*=\s*"([^"]*)")?(?:\s*,\s*risk\s*=\s*FspOperation\.OperationRisk\.([A-Z_]+))?[^)]*\)/g; let m;
  while ((m = re.exec(txt))) {
    const key = m[1];
    if (seen.has(key)) { console.error(`DUPLICATE operation id ${key} in ${rel(f)}`); process.exitCode = 1; continue; }
    seen.add(key);
    ops.push(`| ${m[1]} | ${m[2] && m[2].length ? m[2] : '—(owner-scoped)'} | ${m[4] ?? '—'} | \`${rel(f).split('/').pop()}\` |`);
  }
}

// EMI service operations — same contract shape from its local @EmiOperation declarations
// (routes derived from the descriptor the gateway fetches at emi :8086 /internal/ops/catalog).
const emiOps = ["# Auto-generated — @EmiOperation operations in emi (routed via emi's /internal/ops/catalog desc).", '',
  '| Operation id | Permission | Risk | Source |', '|---|---|---|---|'];
const seenEmi = new Set();
for (const f of walk(path.join(ROOT, 'backend/emi/src'), ['.java'])) {
  const txt = fs.readFileSync(f, 'utf8');
  const re = /@EmiOperation\(\s*id\s*=\s*"([^"]+)"(?:\s*,\s*risk\s*=\s*EmiOperation\.Risk\.([A-Z_]+))?(?:\s*,\s*title\s*=\s*"([^"]*)")?\s*\)/g;
  let m;
  while ((m = re.exec(txt))) {
    const key = m[1];
    if (seenEmi.has(key)) { console.error(`DUPLICATE emi operation id ${key} in ${rel(f)}`); process.exitCode = 1; continue; }
    seenEmi.add(key);
    emiOps.push(`| ${m[1]} | —(owner-scoped) | ${m[2] ?? '—'} | \`${rel(f).split('/').pop()}\` |`);
  }
}
writeIfChanged('operations.md', ops.sort().join('\n') + '\n\n---\n\n' + emiOps.join('\n') + '\n');

// ---------------------------------------------------------------- migrations
const migDir = path.join(ROOT, 'backend/core/src/main/resources/db/migration');
const migs = fs.existsSync(migDir)
  ? fs.readdirSync(migDir).filter((f) => /V\d+__.*\.sql$/.test(f)).sort((a, b) =>
      parseInt(a.match(/V(\d+)/)[1]) - parseInt(b.match(/V(\d+)/)[1])) : [];
let mig = ['# Auto-generated — core Flyway ledger (applied files are immutable; next free number below).', '',
  '```mermaid', 'timeline', '    title Applied migrations'];
for (const f of migs) {
  const [tag, ...rest] = f.replace(/\.sql$/, '').split('__');
  mig.push(`    ${tag.replace('V', 'V')} : ${rest.join('__').replace(/_/g, ' ')}`);
}
const next = Math.max(0, ...migs.map((f) => parseInt(f.match(/V(\d+)/)[1]))) + 1;
mig.push('', `**Next free migration number: V${next}.**`, '```');
writeIfChanged('migrations.md', mig.join('\n') + '\n');

// ---------------------------------------------------------------- summary
if (!check) console.log(`done. operations: ${seen.size}, migrations: ${migs.length}`);
