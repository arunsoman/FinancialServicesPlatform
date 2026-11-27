/** Books of an organization (core LedgerController) — shared by the ledger, period and journal widgets. */
import { useQuery } from '@tanstack/react-query';
import type { WidgetDataPortLike } from '../core/manifest';

export interface BookRow {
  id: string;
  tenantId: string;
  name: string;
  currency: string;
  ledgerId: number;
  status: 'SUSPENDED' | 'ACTIVE' | 'CLOSED';
}

export interface GlAccountRow {
  id: string;
  bookId: string;
  code: number;
  name: string;
  accountType: string;
  normalBalance: string;
  balanceBound: string;
  currency: string;
  status: string;
}

export const ledgerKeys = {
  books: (orgId: string) => ['ledger', orgId, 'books'] as const,
  accounts: (orgId: string, bookId: string) => ['ledger', orgId, 'books', bookId, 'accounts'] as const,
  periods: (orgId: string, bookId: string) => ['ledger', orgId, 'books', bookId, 'periods'] as const,
};

export function useBooks(port: WidgetDataPortLike, orgId: string) {
  return useQuery({
    queryKey: ledgerKeys.books(orgId),
    queryFn: () => port.request<BookRow[]>('ledger.books', { tenantId: orgId }),
    enabled: orgId !== '',
  });
}

/** The book in the URL, else the first ACTIVE one, else the first. */
export function pickBook(books: BookRow[], fromUrl: string | null): BookRow | null {
  return books.find((b) => b.id === fromUrl) ?? books.find((b) => b.status === 'ACTIVE') ?? books[0] ?? null;
}
