package com.fsp.core.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shapes for the settlement bank accounts surface (F-10). Records only (AGENTS §2b). */
public final class BankAccounts {

    private BankAccounts() {}

    /** Maker input — validated field-by-field in the service. */
    public record NewBankAccount(String holderName, String bankCode, String branchCode,
                                 String accountNumber, String accountType, String currency) {}

    /**
     * Wire view. The account number is MASKED to the last 4 (§2c: never echo PII in full);
     * the checker validates the number out-of-band with the maker and the bank registry.
     */
    public record BankAccountView(UUID id, UUID tenantId, String holderName, String bankCode,
                                  String branchCode, String maskedAccountNumber, String accountType,
                                  String currency, String status, UUID approvalId,
                                  String makerName, long version,
                                  Instant createdAt, Instant updatedAt) {}

    public record BankAccountListResponse(List<BankAccountView> items) {}
}
