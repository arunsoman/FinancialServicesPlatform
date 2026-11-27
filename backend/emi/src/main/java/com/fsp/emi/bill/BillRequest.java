package com.fsp.emi.bill;

import java.util.UUID;

/**
 * One outbound bill-payment message. {@code amountMinor} is minor units (money is a
 * string on the wire, §2.3); {@code accountRef} is customer PII on the rails — wire-only,
 * never logged, never stored in audit detail.
 */
public record BillRequest(UUID sagaId, String accountRef, String currency,
                          long amountMinor, String idempotencyKey) {

    /** ISO-4217 minor-unit amount as a string (JS-safe wire format, platform §2b). */
    public String amountOnWire() { return Long.toString(amountMinor); }
}
