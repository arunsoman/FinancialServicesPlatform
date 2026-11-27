import { describe, expect, it } from 'vitest';
import { sortByCreated } from './model';

const rows = [
  { id: 'b.old', createdAt: '2026-10-01T09:00:00Z' },
  { id: 'a.new', createdAt: '2026-11-20T09:00:00Z' },
  { id: 'c.unreg', createdAt: null },
  { id: 'd.mid', createdAt: '2026-11-05T09:00:00Z' },
];
const ids = (r: { id: string }[]) => r.map((x) => x.id);

describe('sortByCreated', () => {
  it('keeps build order unchanged', () => {
    expect(ids(sortByCreated(rows, 'build', (r) => r.createdAt))).toEqual(['b.old', 'a.new', 'c.unreg', 'd.mid']);
  });
  it('newest first puts not-yet-registered widgets on top, then by creation time', () => {
    expect(ids(sortByCreated(rows, 'created-desc', (r) => r.createdAt))).toEqual(['c.unreg', 'a.new', 'd.mid', 'b.old']);
  });
  it('oldest first puts not-yet-registered widgets last', () => {
    expect(ids(sortByCreated(rows, 'created-asc', (r) => r.createdAt))).toEqual(['b.old', 'd.mid', 'a.new', 'c.unreg']);
  });
  it('does not mutate its input and breaks ties by id', () => {
    const same = [{ id: 'z', createdAt: '2026-11-01T00:00:00Z' }, { id: 'y', createdAt: '2026-11-01T00:00:00Z' }];
    expect(ids(sortByCreated(same, 'created-desc', (r) => r.createdAt))).toEqual(['y', 'z']);
    expect(ids(same)).toEqual(['z', 'y']);
  });
});
