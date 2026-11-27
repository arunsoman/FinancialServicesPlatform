/**
 * bun run widgets:skill — regenerates the chat-LLM widget-author skill from the CODE-DERIVED
 * operation catalog (tools/widget-pack/catalog.ts: core @FspOperation handlers + plugin
 * manifests). No running stack needed, and the workbench validator reads the same scan, so the
 * prompt and the validator can never disagree.
 *
 *   1. scans backend/core controllers + backend/plugins/<code>/plugin-manifest.json
 *   2. writes src/widget-workbench/pack/endpoints.json (reference copy + digest)
 *   3. renders docs/skills/fsp-widget-author/SKILL.md from SKILL.template.md
 *
 *   bun run widgets:skill            regenerate both outputs
 *   bun run widgets:skill --check    exit 1 when the committed outputs are stale (CI / verify)
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { digestOf, endpointsOf, scanCatalog } from './catalog';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(HERE, '..', '..');
const REPO = join(FRONTEND, '..');
const ENDPOINTS_FILE = join(FRONTEND, 'src', 'widget-workbench', 'pack', 'endpoints.json');
const SKILL_FILE = join(REPO, 'docs', 'skills', 'fsp-widget-author', 'SKILL.md');
const TEMPLATE = join(HERE, 'SKILL.template.md');

function main() {
  const check = process.argv.includes('--check');
  const endpoints = endpointsOf(scanCatalog(REPO));
  const digest = digestOf(endpoints);

  if (check) {
    const current = existsSync(ENDPOINTS_FILE) ? (JSON.parse(readFileSync(ENDPOINTS_FILE, 'utf8')) as { digest?: string }).digest : null;
    if (current !== digest) {
      console.error(`widgets:skill --check — STALE: endpoints.json digest ${String(current).slice(0, 12)} ≠ code ${digest.slice(0, 12)}. Run: bun run widgets:skill`);
      process.exit(1);
    }
    console.log(`widgets:skill --check — up to date (${endpoints.length} endpoints, digest ${digest.slice(0, 12)})`);
    return;
  }

  mkdirSync(dirname(ENDPOINTS_FILE), { recursive: true });
  writeFileSync(ENDPOINTS_FILE, JSON.stringify({ digest, endpoints }, null, 2) + '\n');

  const table = [
    '| Method | BFF path | perm | risk | what |',
    '|---|---|---|---|---|',
    ...endpoints.map((e) => `| ${e.method} | \`${e.path}\` | ${e.permission ? `\`${e.permission}\`` : '—'} | ${e.risk} | ${e.title} |`),
  ].join('\n');

  const skill = readFileSync(TEMPLATE, 'utf8')
    .replace('{{ENDPOINTS}}', table)
    .replace('{{GENERATED_AT}}', new Date().toISOString().slice(0, 10))
    .replace('{{CATALOG_DIGEST}}', digest.slice(0, 12));
  mkdirSync(dirname(SKILL_FILE), { recursive: true });
  writeFileSync(SKILL_FILE, skill);
  console.log(`widgets:skill — ${endpoints.length} endpoints → ${ENDPOINTS_FILE}\n               skill → ${SKILL_FILE}`);
}

main();
