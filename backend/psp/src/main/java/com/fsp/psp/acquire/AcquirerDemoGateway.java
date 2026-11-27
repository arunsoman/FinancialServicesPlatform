package com.fsp.psp.acquire;

import com.fsp.psp.connector.BankConnector;
import com.fsp.shared.rails.RailResult;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * DEMO protocol adapter: a deterministic in-process acquirer used for sandbox/demo
 * profiles (message_format = 'DEMO'). Accepts every authorization except refs starting
 * with "FAIL-" (deterministic rejection); rail refs are memorable and stable so
 * statements can be crafted against them.
 */
@Component
public class AcquirerDemoGateway implements AcquirerGateway {

    @Override
    public RailResult authorize(BankConnector connector, AcquireRequest request) {
        if (request.pspRef() == null || request.pspRef().startsWith("FAIL-")) {
            return new RailResult.Rejected("PSP_DEMO_REJECTED", true);
        }
        return new RailResult.Accepted(
            "demo-" + connector.bankCode + "-" + request.pspRef(), Instant.now());
    }
}
