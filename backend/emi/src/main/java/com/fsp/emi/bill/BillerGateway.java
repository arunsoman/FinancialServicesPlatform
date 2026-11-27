package com.fsp.emi.bill;

import com.fsp.shared.rails.RailResult;

import java.util.Optional;

/**
 * Biller rail gateway seam (chapter 13 Stage 3 — protocol support). Adapters turn biller
 * dialects into the sealed Stage-1 {@link RailResult} alphabet; orchestration state
 * machines never see raw HTTP/ISO dialects. Implementations MUST run every network call
 * through the Stage-1 {@code RailGuard} admission ladder and MUST NOT log account numbers,
 * credentials, or bodies.
 */
public interface BillerGateway {

    /**
     * Submits one bill payment to the biller. May throw {@code RailTransportException}
     * (transport failure with a failure-alphabet kind — the submit outcome is then
     * <b>unknown</b>: the biller may or may not have seen the request).
     */
    RailResult submit(Biller biller, BillRequest request);

    /**
     * Recon status probe against a previously acknowledged rail ref.
     * Empty = inconclusive (rail unreachable / still in flight).
     */
    Optional<RailResult> probe(Biller biller, BillRequest request, String railRef);
}
