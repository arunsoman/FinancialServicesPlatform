/**
 * Links between widgets resolve against THIS portal's published menus. A hard-coded path
 * ('/kyc/reviews', '/users') has no route in a published portal and silently landed on the
 * portal's first screen (User Management) — the KYC "Decide" link and the org-list click.
 */
import { describe, expect, it } from 'vitest';
import { menuCell, widgetScreenLink } from './signed-layout';
import type { MenuAccess, SignedLayout } from './signed-layout';
import { subjectLink } from '../shared/widgets/approvals/queue/model';
import type { QueueRow } from '../shared/widgets/approvals/queue/model';

const layout: SignedLayout = {
  portal: 'admin', schemaVersion: 1, layoutVersion: 'l', publishedAt: '2026-11-24T00:00:00Z',
  menus: [
    { id: 'user-management', label: 'User Management', screens: [
      { id: 'back-office-users', label: 'Back Office User', placedWidgets: [{ id: 'a', widgetId: 'users.organization', span: {} }] },
      { id: 'kyc-reviews', label: 'KYC review', placedWidgets: [{ id: 'b', widgetId: 'kyc.manage', span: {} }] },
    ] },
  ],
  widgets: [
    { id: 'users.organization', version: '1.0.0', bundle: '', bundleHash: '', requires: [] },
    { id: 'kyc.manage', version: '1.0.0', bundle: '', bundleHash: '', requires: [] },
  ],
};
const full: MenuAccess = { full: true, cells: new Set() };
const row = (operationType: string, targetResourceId: string | null = 'c1'): QueueRow =>
  ({ operationType, targetResourceId } as unknown as QueueRow);

describe('widgetScreenLink', () => {
  it('finds the screen that places the widget', () => {
    expect(widgetScreenLink(layout, 'kyc.manage', full)).toBe('/p/user-management/kyc-reviews');
  });
  it('is null when the portal does not offer the widget, or the person lacks the screen', () => {
    expect(widgetScreenLink(layout, 'journal.entry', full)).toBeNull();
    const onlyUsers: MenuAccess = { full: false, cells: new Set([menuCell('user-management', 'back-office-users', 'ACTIVE')]) };
    expect(widgetScreenLink(layout, 'kyc.manage', onlyUsers)).toBeNull();
    expect(widgetScreenLink(layout, 'users.organization', onlyUsers)).toBe('/p/user-management/back-office-users');
  });
});

describe('approval card subject links name a widget, never a path', () => {
  it('a KYC decision opens the case in the KYC review widget', () => {
    expect(subjectLink(row('PLUGIN:kyc:CASE_DECISION', 'case-9'))).toEqual({ widgetIds: ['kyc.manage', 'onboard.list'], search: '?case=case-9', label: 'Open the KYC case to decide' });
  });
  it('other requests open their record', () => {
    expect(subjectLink(row('USER_UPDATE'))?.widgetIds).toEqual(['users.organization']);
    expect(subjectLink(row('BRANCH_CREATE'))?.search).toBe('?branch=c1');
    expect(subjectLink(row('UNKNOWN_TYPE'))).toBeNull();
  });
});
