import { describe, expect, it } from 'vitest';
import { isEmpty } from './ports';

describe('isEmpty (workbench frame state + live call records)', () => {
  it('treats null, [] and an empty page envelope as empty', () => {
    expect(isEmpty(null)).toBe(true);
    expect(isEmpty(undefined)).toBe(true);
    expect(isEmpty([])).toBe(true);
    expect(isEmpty({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 1, hasNext: false })).toBe(true);
  });

  it('treats rows, a non-empty page and a plain object as data', () => {
    expect(isEmpty([1])).toBe(false);
    expect(isEmpty({ items: [{ id: 'a' }] })).toBe(false);
    expect(isEmpty({ outstandingMinor: '0' })).toBe(false);
  });
});
