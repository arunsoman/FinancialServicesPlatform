import { describe, expect, it } from 'vitest';
import { previewUrl } from './preview-window';
import { newestServerLayout, sameLayout } from './server-sync';
import type { LayoutView } from './server-layouts';

const view = (status: string, updatedAt: string, label: string): LayoutView => ({
  id: status, portal: 'admin', status, schemaVersion: 1, makerId: 'm', versionNum: 1, updatedAt,
  layoutJson: JSON.stringify({ portal: 'admin', menus: [{ id: 'm', label, screens: [] }] }),
});

describe('newestServerLayout (what the builder opens = what the export takes)', () => {
  it('takes the newer of draft and published', () => {
    const draft = view('DRAFT', '2026-11-23T10:00:00Z', 'draft');
    const pub = view('PUBLISHED', '2026-11-22T10:00:00Z', 'published');
    expect(newestServerLayout('admin', draft, pub)?.menus[0].label).toBe('draft');
    expect(newestServerLayout('admin', view('DRAFT', '2026-11-21T10:00:00Z', 'old'), pub)?.menus[0].label).toBe('published');
  });

  it('works with either missing, and with neither', () => {
    expect(newestServerLayout('admin', null, view('PUBLISHED', '2026-11-22T10:00:00Z', 'p'))?.menus[0].label).toBe('p');
    expect(newestServerLayout('admin', null, null)).toBeNull();
  });
});

describe('previewUrl', () => {
  it('opens each portal on its own entry, in preview mode', () => {
    expect(previewUrl('admin', 'http://localhost:5173')).toBe('http://localhost:5173/admin/?preview=1');
    expect(previewUrl('merchant', 'http://localhost:5173')).toBe('http://localhost:5173/merchant/?preview=1');
  });
});

describe('sameLayout', () => {
  it('ignores key order (the server re-serialises what it stores)', () => {
    const a = { portal: 'admin', menus: [{ id: 'm', label: 'M', screens: [] }] };
    const b = { menus: [{ screens: [], label: 'M', id: 'm' }], portal: 'admin' };
    expect(sameLayout(a, b)).toBe(true);
    expect(sameLayout(a, { ...a, menus: [{ id: 'm', label: 'Other', screens: [] }] })).toBe(false);
  });
});
