package com.fsp.psp.acquire;

import com.fsp.psp.connector.BankConnector;
import com.fsp.shared.rails.RailResult;

/**
 * The acquirer rail seam. Implementations own the wire protocol (REST / demo) and run
 * every call through the Stage-1 RailGuard admission ladder (429 freeze → breaker →
 * bulkhead). Services never see HTTP codes — only {@link RailResult} seals plus
 * {@code RailTransportException} for unknown-outcome transports.
 */
public interface AcquirerGateway {

    /**
     * Synchronous authorization: the rail decides Accepted (funds reserved on the
     * customer's instrument) or Rejected. Unknown-outcome transports throw
     * {@code RailTransportException} — the saga stays CREATED and replays deterministically.
     */
    RailResult authorize(BankConnector connector, AcquireRequest request);

    /** Wire request for one acquiring authorization. */
    record AcquireRequest(String pspRef, long amountMinor, String currency, String merchantCode) {}
}
