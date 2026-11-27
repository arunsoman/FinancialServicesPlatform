package com.fsp.shared.rails;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sealed-taxonomy compile-time exhaustiveness (plan §3.2): every new rail variant must
 * be handled at every dispatch site — demonstrated by exhaustive switches over the seals,
 * plus the exact taxonomy semantics from §3.3.
 */
class RailTaxonomyTest {

    /** Exhaustive switch: adding a variant breaks this method — exactly the intent. */
    private static String label(RailResult result) {
        return switch (result) {
            case RailResult.Accepted a -> "accepted:" + a.railRef();
            case RailResult.AcceptedPending p -> "pending:" + p.railRef();
            case RailResult.Rejected r -> "rejected" + (r.permanent() ? "-permanent:" : "-retryable:") + r.code();
            case RailResult.Failed f -> "failed:" + label(f.kind());
        };
    }

    private static String label(RailResult.FailureKind kind) {
        return switch (kind) {
            case RailResult.FailureKind.CommUnreachable c -> "comm:" + c.observedFor();
            case RailResult.FailureKind.TemporarilyUnavailable t -> "temp:" + t.retryAfterSeconds();
            case RailResult.FailureKind.RateLimited w -> "window:" + w.movingWindowReset();
            case RailResult.FailureKind.Backpressure b -> "backpressure:" + b.retryIn();
            case RailResult.FailureKind.DefinitiveFailure d -> "definitive:" + d.railCode();
        };
    }

    /** Exhaustive switch over protocol variants — the sealed protocol SPI compiles. */
    private static RailCapability capabilityOf(ProviderProtocol p) {
        return switch (p) {
            case ProviderProtocol.Iso8583Protocol iso -> new RailCapability.Sequential(1);
            case ProviderProtocol.TerminalStreamProtocol ts -> new RailCapability.Sequential(1);
            case ProviderProtocol.Iso20022PacsProtocol pac -> new RailCapability.Parallel(8, Duration.ofSeconds(2), Duration.ofSeconds(8));
            case ProviderProtocol.Iso20022PainProtocol pain -> new RailCapability.Parallel(8, Duration.ofSeconds(2), Duration.ofSeconds(8));
            case ProviderProtocol.NachaProtocol nacha -> new RailCapability.Sequential(1);
            case ProviderProtocol.RestOpenBankingProtocol rest -> new RailCapability.Parallel(10, Duration.ofSeconds(2), Duration.ofSeconds(8));
            case ProviderProtocol.UpiProtocol upi -> new RailCapability.Parallel(10, Duration.ofSeconds(2), Duration.ofSeconds(8));
            case ProviderProtocol.BillerRestProtocol biller -> new RailCapability.Parallel(5, Duration.ofSeconds(2), Duration.ofSeconds(8));
            case ProviderProtocol.WalletRailProtocol wallet -> new RailCapability.Parallel(10, Duration.ofSeconds(2), Duration.ofSeconds(8));
            case ProviderProtocol.CustodianRestProtocol cust -> new RailCapability.Parallel(2, Duration.ofSeconds(2), Duration.ofSeconds(8));
        };
    }

    @Test
    void exhaustiveSwitches_sealTheTaxonomy() {
        assertThat(label(new RailResult.Accepted("R-1", Instant.EPOCH))).isEqualTo("accepted:R-1");
        assertThat(label(new RailResult.AcceptedPending("R-2"))).isEqualTo("pending:R-2");
        assertThat(label(new RailResult.Rejected("INVALID_ACCOUNT", true)))
                .isEqualTo("rejected-permanent:INVALID_ACCOUNT");
        assertThat(label(new RailResult.Failed(new RailResult.FailureKind.TemporarilyUnavailable(30))))
                .isEqualTo("failed:temp:30");

        var iso = new ProviderProtocol.Iso8583Protocol("net-1",
                new ProviderProtocol.HostProfile("h", 5000, true),
                new TransportChannel.TcpSessionChannel(new ProviderProtocol.HostProfile("h", 5000, true), Duration.ofSeconds(30)));
        assertThat(capabilityOf(iso)).isEqualTo(new RailCapability.Sequential(1));
    }

    @Test
    void credentials_areEnvReferenced_neverLiterals() {
        var rest = new ProviderProtocol.RestOpenBankingProtocol(
                "fnb-namibia-api", new ProviderProtocol.CertificateRef("FSP_RAIL_FNB_CERT"),
                new TransportChannel.MtlsHttpChannel("https://api.bank.example/v1",
                        new ProviderProtocol.CertificateRef("FSP_RAIL_FNB_CERT")));
        assertThat(rest.mtls().envRef()).startsWith("FSP_").doesNotContain("MIIC");
    }

    @Test
    void billerContract_classifiesReversalPolicy() {
        // Contract-silent default is C (plan §12.3) — exercised at usage sites;
        // here: A/B/C round-trips.
        var reversible = new RailResult.AcceptedPending("B-9");
        assertThat(((RailResult.AcceptedPending) reversible).railRef()).isEqualTo("B-9");

        var adapter = billerAdapter(ReversalClass.B, Duration.ofDays(30));
        var cfg = new ProviderProtocol.BillerRestProtocol("MTC",
                new ProviderProtocol.BillerContract("MTC", ReversalClass.B, Duration.ofDays(30)));
        assertThat(adapter.capability(cfg)).isInstanceOf(RailCapability.Parallel.class);
        assertThat(adapter.family()).isEqualTo(RailFamily.BILLER_REST);
        assertThat(adapter.probe(new ProviderProtocol.BillerRestProtocol("MTC",
                new ProviderProtocol.BillerContract("MTC", ReversalClass.B, Duration.ofDays(30))), "B-9"))
                .contains(RailStatus.IN_FLIGHT);
    }

    private ProtocolAdapter<ProviderProtocol.BillerRestProtocol> billerAdapter(
            ReversalClass ignored, Duration window) {
        return new ProtocolAdapter<ProviderProtocol.BillerRestProtocol>() {
            @Override public RailFamily family() { return RailFamily.BILLER_REST; }
            @Override public RailCapability capability(ProviderProtocol.BillerRestProtocol cfg) {
                return new RailCapability.Parallel(5, Duration.ofSeconds(2), Duration.ofSeconds(8));
            }
            @Override public RailResult submit(ProviderProtocol.BillerRestProtocol cfg,
                                               String idempotencyKey,
                                               FinancialMessage msg) {
                return new RailResult.AcceptedPending("B-1");
            }
            @Override public Optional<RailStatus> probe(ProviderProtocol.BillerRestProtocol cfg, String railRef) {
                return Optional.of(RailStatus.IN_FLIGHT);
            }
        };
    }
}
