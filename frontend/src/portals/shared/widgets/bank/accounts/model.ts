/** bank.accounts — shapes, keys and static registry (non-component parts, F-10). */

/** static Namibian bank registry (BoN) — same codes the backend validates. */
export const BANKS: Record<string, string> = {
  BANK_WINDHOEK: 'Bank Windhoek',
  FNB_NAMIBIA: 'First National Bank Namibia',
  NEDBANK_NAMIBIA: 'Nedbank Namibia',
  STANDARD_BANK: 'Standard Bank Namibia',
  BANK_BIC: 'Bank BIC Namibia',
  LETSHEGO: 'Letshego Bank Namibia',
  BANCO_ATLANTICO: 'Banco Atlántico (foreign)',
};

export interface BankAccountRow {
  id: string;
  tenantId: string;
  holderName: string;
  bankCode: string;
  branchCode: string;
  maskedAccountNumber: string;
  accountType: string;
  currency: string;
  status: 'PENDING' | 'ACTIVE' | 'REPLACED' | 'REJECTED';
  approvalId: string | null;
  makerName: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface BankAccountsResponse {
  items: BankAccountRow[];
}

export function bankKeys(orgId: string): { root: readonly [string, string, string] } {
  return { root: ['bank', 'accounts', orgId] };
}

export const STATUS_TONE: Record<BankAccountRow['status'], 'ok' | 'warn' | 'bad' | 'neutral'> = {
  ACTIVE: 'ok', PENDING: 'warn', REPLACED: 'neutral', REJECTED: 'bad',
};
