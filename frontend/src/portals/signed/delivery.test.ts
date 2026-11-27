/**
 * GAP-NO-MENU probes: a portal's menu comes only from its published layout. No layout → the
 * portal says "No menu configured" (never a coded fallback menu); a bad payload or a server error
 * is "couldn't load", with a retry — never a tamper screen.
 */
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { loadMenus } from './delivery';
import { NoMenuConfigured } from './NoMenuConfigured';
import { MenusUnavailable } from './MenusUnavailable';

const failWith = (status: number) => () => Promise.reject(Object.assign(new Error('x'), { status }));
const menus = (portal: string) => ({
  portal, schemaVersion: 1, layoutVersion: 'l1', publishedAt: '2026-11-24T05:00:00Z',
  menus: [{ id: 'billing', label: 'Billing', screens: [{ id: 'invoices', label: 'Invoices', placedWidgets: [] }] }],
  widgets: [],
});

describe('GAP-NO-MENU · loading outcomes', () => {
  it('published menus load as they are', async () => {
    const d = await loadMenus('merchant', () => Promise.resolve(menus('merchant')));
    expect(d.kind).toBe('layout');
  });
  it('no published layout (404) is "missing"', async () => {
    expect(await loadMenus('merchant', failWith(404))).toEqual({ kind: 'missing' });
  });
  it('an expired session (401) forces re-authentication', async () => {
    expect(await loadMenus('merchant', failWith(401))).toEqual({ kind: 'reauth' });
  });
  it('a server error is thrown so the gate can offer a retry', async () => {
    await expect(loadMenus('merchant', failWith(503))).rejects.toMatchObject({ status: 503 });
  });
  it('a payload of the wrong shape, or for another portal, is "invalid" with a reason in words', async () => {
    expect(await loadMenus('merchant', () => Promise.resolve({ portal: 'merchant' }))).toMatchObject({ kind: 'invalid' });
    expect(await loadMenus('merchant', () => Promise.resolve(menus('admin')))).toMatchObject({ kind: 'invalid', reason: expect.stringContaining('admin') });
  });
});

describe('GAP-NO-MENU · the portal says so', () => {
  it('names the portal and that no menu is configured', () => {
    const html = renderToStaticMarkup(createElement(NoMenuConfigured, { label: 'Merchant' }));
    expect(html).toContain('No menu configured for this portal');
    expect(html).toContain('Merchant');
  });
  it('a load failure offers a retry, never a tamper verdict', () => {
    const html = renderToStaticMarkup(createElement(MenusUnavailable, { label: 'Merchant', detail: 'x', onRetry: () => undefined }));
    expect(html).toContain('Couldn’t load the Merchant menus');
    expect(html).toContain('Try again');
    expect(html.toLowerCase()).not.toContain('tamper');
  });
});
