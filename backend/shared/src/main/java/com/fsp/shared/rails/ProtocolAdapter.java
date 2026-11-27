package com.fsp.shared.rails;

import java.util.Optional;

/**
 * One adapter class per rail FAMILY (~6 classes for N banks — plan §3.2): profile rows
 * (transport channel, message format, dictionary version) parameterise behaviour, never
 * per-bank code. Implementations must be thread-safe: {@code call} may run concurrently
 * only as admitted by the {@link RailCapability}.
 */
public interface ProtocolAdapter<P extends ProviderProtocol> {

    RailFamily family();

    RailCapability capability(P cfg);

    /**
     * Idempotent by (idempotency-key, counterparty-ref) — never duplicates money.
     * Adapters classify everything into {@link RailResult}; they never leak transports.
     * The caller MUST NOT invoke this without an admission lease from {@link RailGuard}
     * (breaker/bulkhead/window discipline is enforced centrally, not per adapter).
     */
    RailResult submit(P cfg, String idempotencyKey, FinancialMessage msg);

    /** Safe at any time: ask the counterparty what actually happened (plan §5.3 disambiguation). */
    Optional<RailStatus> probe(P cfg, String railRef);

    /** Transport-neutral financial instruction (minor units + ISO-4217 alpha-3). */
    record FinancialMessage(long amountMinor, String currencyIso,
                            String debtorRef, String creditorRef, String narrative) {}
}
