import { describe, expect, it } from 'vitest';
import { registrationState } from './registration';

const def = { id: 'billing.invoices', version: '1.1.0' };
const ok = (data: { id: string; version: string }[]) => ({ data, isLoading: false, error: null });

describe('registrationState — where a widget stands against core’s registry', () => {
  it('asks to sign in first', () => {
    expect(registrationState(def, false, ok([]))).toEqual({ kind: 'signed-out' });
  });
  it('waits while the registry loads, and says so when it cannot answer', () => {
    expect(registrationState(def, true, { data: undefined, isLoading: true, error: null })).toEqual({ kind: 'loading' });
    expect(registrationState(def, true, { isLoading: false, error: new Error('x'), detail: 'down' }))
      .toEqual({ kind: 'unavailable', detail: 'down' });
  });
  it('tells missing, outdated and registered apart', () => {
    expect(registrationState(def, true, ok([{ id: 'other.widget', version: '1.0.0' }]))).toEqual({ kind: 'missing' });
    expect(registrationState(def, true, ok([{ id: 'billing.invoices', version: '1.0.0' }])))
      .toEqual({ kind: 'outdated', serverVersion: '1.0.0' });
    expect(registrationState(def, true, ok([{ id: 'billing.invoices', version: '1.1.0' }])))
      .toEqual({ kind: 'registered', version: '1.1.0' });
  });
});
