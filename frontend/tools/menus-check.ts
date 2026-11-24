/**
 * menus:check — CI gate for the portal menus that ship with the code (docs/portal-menus.md).
 * Reads the repo seed (R__seed_portal_menus.sql) without a database and fails when a portal
 * would not render after deploy:
 *   - a layout does not have the shape the portal accepts (the same zod schema it verifies with);
 *   - a placed widget is not in the seed's widget list (the signed bundle would not approve it);
 *   - a placed widget is not in this build, or its major version differs (it would vanish).
 *
 *   bun run menus:check
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { signedLayoutSchema } from '../src/portals/signed/signed-layout';

const FRONTEND = dirname(dirname(fileURLToPath(import.meta.url)));
const SEED = join(dirname(FRONTEND), 'backend/core/src/main/resources/db/seed/system/R__seed_portal_menus.sql');
const WIDGETS = join(FRONTEND, 'src/portals/shared/widgets');

const unquote = (s: string) => s.replace(/''/g, "'");
const major = (v: string) => v.split('.')[0];

/** Every build widget: id → version, read from each widget folder's manifest. */
function buildWidgets(): Map<string, string> {
  const out = new Map<string, string>();
  for (const area of readdirSync(WIDGETS, { withFileTypes: true })) {
    if (!area.isDirectory()) continue;
    for (const w of readdirSync(join(WIDGETS, area.name), { withFileTypes: true })) {
      const f = join(WIDGETS, area.name, w.name, 'index.tsx');
      if (!w.isDirectory() || !existsSync(f)) continue;
      const src = readFileSync(f, 'utf8');
      const id = /\bid:\s*'([^']+)'/.exec(src)?.[1];
      const version = /\bversion:\s*'([^']+)'/.exec(src)?.[1];
      if (id && version) out.set(id, version);
    }
  }
  return out;
}

export function checkSeed(sql: string, build: Map<string, string>): string[] {
  const problems: string[] = [];
  const seeded = new Map<string, string>();
  for (const m of sql.matchAll(/INSERT INTO ui_widgets [^\n]*\nVALUES \('((?:[^']|'')+)', '((?:[^']|'')+)'/g)) {
    seeded.set(unquote(m[1]), unquote(m[2]));
  }
  const layouts = [...sql.matchAll(/-- portal: (\S+)\nINSERT INTO ui_layouts [^\n]*\nVALUES \('[^']*', '([^']*)', 'PUBLISHED', '((?:[^']|'')*)', \d+,/g)];
  if (layouts.length === 0) problems.push('seed has no portal layouts');
  for (const [, label, portal, raw] of layouts) {
    let layout: { menus?: { id: string; screens: { id: string; placedWidgets: { widgetId: string }[] }[] }[] };
    try {
      layout = JSON.parse(unquote(raw));
    } catch {
      problems.push(`${label}: layout is not valid JSON`);
      continue;
    }
    const shape = signedLayoutSchema.safeParse({
      portal, schemaVersion: 1, layoutVersion: 'ci', publishedAt: 'ci', menus: layout.menus, widgets: [],
    });
    if (!shape.success) {
      const i = shape.error.issues[0];
      problems.push(`${portal}: layout shape the portal rejects at ${i.path.join('.')} — ${i.message}`);
      continue;
    }
    for (const menu of shape.data.menus) {
      for (const screen of menu.screens) {
        for (const p of screen.placedWidgets) {
          const where = `${portal} › ${menu.id} › ${screen.id}: ${p.widgetId}`;
          const sv = seeded.get(p.widgetId);
          const bv = build.get(p.widgetId);
          if (!sv) problems.push(`${where} is placed but not in the seed's widget list`);
          if (!bv) problems.push(`${where} is placed but this build has no such widget`);
          if (sv && bv && major(sv) !== major(bv)) {
            problems.push(`${where} seed version ${sv} vs build ${bv} — major differs, it would not render`);
          }
        }
      }
    }
  }
  return problems;
}

if (import.meta.main) {
  const problems = checkSeed(readFileSync(SEED, 'utf8'), buildWidgets());
  if (problems.length) {
    console.error(`✗ portal menus would not render after deploy (${problems.length}):`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log('✓ portal menus: every portal layout renders with this build');
}
