import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { checkSeed } from './menus-check';

const layout = (widgetId: string, screens = true) => JSON.stringify({
  portal: 'merchant',
  menus: [{ id: 'ops', label: 'Ops', screens: screens
    ? [{ id: 'home', label: 'Home', placedWidgets: [{ id: 'w1', widgetId, span: { lg: 12 }, config: {} }] }]
    : [] }],
}, null, 4).replace(/'/g, "''");

const seed = (widgetRow: string, json: string) => `
INSERT INTO ui_widgets (id, version, label, category, bundle, portals, requires, status, registered_by, bundle_sha256)
VALUES (${widgetRow}, 'L', 'C', 'widgets/x', '["merchant"]', '[]', 'ACTIVE', '00000000-0000-0000-0000-000000000201', NULL)
ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version;
-- portal: merchant
INSERT INTO ui_layouts (id, portal, status, layout_json, schema_version, maker_id)
VALUES ('1f04eabd-9214-d45d-c84c-71b8c3221e97', 'merchant', 'PUBLISHED', '${json}', 1, '00000000-0000-0000-0000-000000000201')
ON CONFLICT (portal) WHERE status = 'PUBLISHED' DO UPDATE SET layout_json = EXCLUDED.layout_json;
`;

const BUILD = new Map([['kyc.orgs', '1.2.0']]);

describe('menus:check', () => {
  it('passes a seed that renders', () => {
    expect(checkSeed(seed("'kyc.orgs', '1.0.0'", layout('kyc.orgs')), BUILD)).toEqual([]);
  });

  it('catches a placed widget the build no longer has', () => {
    const p = checkSeed(seed("'gone.widget', '1.0.0'", layout('gone.widget')), BUILD);
    expect(p.join()).toMatch(/this build has no such widget/);
  });

  it('catches a placed widget missing from the seed widget list', () => {
    expect(checkSeed(seed("'other.one', '1.0.0'", layout('kyc.orgs')), BUILD).join()).toMatch(/not in the seed's widget list/);
  });

  it('catches a major version mismatch (the widget would silently vanish)', () => {
    expect(checkSeed(seed("'kyc.orgs', '2.0.0'", layout('kyc.orgs')), BUILD).join()).toMatch(/major differs/);
  });

  it('catches a layout shape the portal would reject (a menu without screens)', () => {
    expect(checkSeed(seed("'kyc.orgs', '1.0.0'", layout('kyc.orgs', false)), BUILD).join()).toMatch(/shape the portal rejects/);
  });

  it('reads all five portals from the real seed', () => {
    const sql = readFileSync(new URL('../../backend/core/src/main/resources/db/seed/system/R__seed_portal_menus.sql', import.meta.url), 'utf8');
    const p = checkSeed(sql, new Map()); // empty build → every placement is reported, per portal
    for (const portal of ['admin', 'distributor', 'manufacturer', 'merchant', 'wholesaler']) {
      expect(p.some((x) => x.startsWith(`${portal} ›`))).toBe(true);
    }
  });
});
