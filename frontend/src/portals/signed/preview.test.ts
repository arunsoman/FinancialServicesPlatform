/**
 * Builder preview (preview.ts): the builder's editor copy renders through the SAME layout
 * pipeline as a signed delivery, every build widget counts as approved, and a tab only turns
 * into a preview when asked (`?preview=1`), for exactly one portal.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { span } from '../shared/widgets/core/grid';
import type { WidgetDef } from '../shared/widgets/core/manifest';
import { previewActive, previewLayoutFrom } from './preview';
import { placedDef, screenVisible } from './signed-layout';
import { PREVIEW_ACCESS } from './preview';

const def = (id: string, version: string): WidgetDef => ({
  id, version, label: id, contexts: ['ANY'], requires: ['kyc.review'],
  size: { xs: span.xs(4), sm: span.sm(4, 8, 8), md: span.md(6, 6, 12), lg: span.lg(4, 6, 12) },
  render: () => null,
});
const BUILD = [def('kyc.orgs', '1.2.0')];

const draft = (portal = 'merchant') => JSON.stringify({
  portal,
  menus: [
    { id: 'onboarding', label: 'Onboarding', screens: [
      { id: 'network', label: 'My network', placedWidgets: [{ id: 'w1', widgetId: 'kyc.orgs', span: { lg: 6 }, config: {} }] },
    ] },
    { id: 'empty', label: 'Not yet', screens: [] },
  ],
});

describe('previewLayoutFrom', () => {
  it('turns the builder copy into a renderable layout with the build widgets approved', () => {
    const l = previewLayoutFrom(draft(), 'merchant', BUILD);
    expect(l).not.toBeNull();
    expect(l!.menus.map((m) => m.id)).toEqual(['onboarding']); // a menu without submenus never shows
    const menu = l!.menus[0];
    const screen = menu.screens[0];
    expect(placedDef(l!, screen.placedWidgets[0], (id) => BUILD.find((d) => d.id === id))?.id).toBe('kyc.orgs');
    expect(screenVisible(l!, menu, screen, ['kyc.review'], (id) => BUILD.find((d) => d.id === id), PREVIEW_ACCESS)).toBe(true);
  });

  it('still applies the widget requires: no permission, no screen', () => {
    const l = previewLayoutFrom(draft(), 'merchant', BUILD)!;
    expect(screenVisible(l, l.menus[0], l.menus[0].screens[0], [], (id) => BUILD.find((d) => d.id === id), PREVIEW_ACCESS)).toBe(false);
  });

  it('never previews another portal\'s copy, nothing, or a copy with a broken shape', () => {
    expect(previewLayoutFrom(draft('admin'), 'merchant', BUILD)).toBeNull();
    expect(previewLayoutFrom(null, 'merchant', BUILD)).toBeNull();
    expect(previewLayoutFrom('{not json', 'merchant', BUILD)).toBeNull();
    expect(previewLayoutFrom(draft().replace('"network"', '"Bad Id"'), 'merchant', BUILD)).toBeNull();
  });
});

describe('previewActive', () => {
  // the unit runner has no DOM: a tab = a URL + its own sessionStorage
  let url = '/';
  const at = (u: string) => {
    url = u;
  };
  beforeEach(() => {
    const store = new Map<string, string>();
    vi.stubGlobal('window', {
      get location() {
        return new URL(url, 'http://localhost:5173');
      },
    });
    vi.stubGlobal('sessionStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('is off unless the tab was opened with ?preview', () => {
    at('/merchant/');
    expect(previewActive('merchant')).toBe(false);
  });

  it('stays on for this tab after navigating away from ?preview (sign-in round trip)', () => {
    at('/merchant/?preview=1');
    expect(previewActive('merchant')).toBe(true);
    at('/merchant/p/onboarding/network');
    expect(previewActive('merchant')).toBe(true);
    expect(previewActive('admin')).toBe(false); // one portal per tab
  });

  it('?preview=off turns it back', () => {
    at('/merchant/?preview=1');
    previewActive('merchant');
    at('/merchant/?preview=off');
    expect(previewActive('merchant')).toBe(false);
  });
});
