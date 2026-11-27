package com.fsp.emi.bill;

import com.fsp.shared.rails.RailResult;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Demo biller (@Profile demo-biller): instant acceptance + settled probes, for the
 * real-TB Stage-3 smoke runner only. Demo rail ref is deterministic per saga.
 * Runtime demo data lives in @Profile runners — never in SQL seed.
 */
@Primary
@Service
@Profile("demo-biller")
public class BillerDemoGateway implements BillerGateway {

    @Override
    public RailResult submit(Biller biller, BillRequest request) {
        return new RailResult.Accepted("demo-biller-ref-" + request.sagaId(), null);
    }

    @Override
    public Optional<RailResult> probe(Biller biller, BillRequest request, String railRef) {
        return Optional.of(new RailResult.Accepted(railRef, null));
    }
}
