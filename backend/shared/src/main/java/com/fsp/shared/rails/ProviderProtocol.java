package com.fsp.shared.rails;

import java.time.Duration;

/**
 * The one true protocol SPI (plan §3.2, chapter 13 §2). Exhaustive switch at compile time —
 * a new rail forces every dispatch site to handle it. Sealed so no connector implementation
 * can slip an unknown protocol past the dispatch ladder.
 *
 * <p>Dispatch is driven by {@link RailCapability} (capability, not instance). Credentials
 * are referenced by <b>env-var name only</b> ({@code *Ref} records) — secrets never live in
 * config rows, logs, or code (platform rule: zero token/secret retention).</p>
 */
public sealed interface ProviderProtocol {

    /** Session-oriented card networks / ISO 8583 terminals. */
    sealed interface SessionBound extends ProviderProtocol
            permits Iso8583Protocol, TerminalStreamProtocol {}

    /** Message-based interbank rails (ISO 20022 pain/pacs, NACHA batch). */
    sealed interface MessageTyped extends ProviderProtocol
            permits Iso20022PacsProtocol, Iso20022PainProtocol, NachaProtocol {}

    /** Modern HTTP/MQ rails. */
    sealed interface HttpBased extends ProviderProtocol
            permits RestOpenBankingProtocol, UpiProtocol, BillerRestProtocol,
                    WalletRailProtocol, CustodianRestProtocol {}

    record Iso8583Protocol(String networkId, HostProfile host, TransportChannel transport) implements SessionBound {}
    record TerminalStreamProtocol(String terminalId, HostProfile host, TransportChannel transport) implements SessionBound {}
    record Iso20022PacsProtocol(String bic, CertificateRef cert, TransportChannel transport) implements MessageTyped {}
    record Iso20022PainProtocol(String bic, CertificateRef cert, TransportChannel transport,
                                String dictionaryVersion) implements MessageTyped {}
    record NachaProtocol(String companyNameEntry, FtpProfile ftp) implements MessageTyped {}
    record RestOpenBankingProtocol(String apiProfile, CertificateRef mtls, TransportChannel transport) implements HttpBased {}
    record UpiProtocol(String pspHandle, CertificateRef cert, TransportChannel transport) implements HttpBased {}
    record BillerRestProtocol(String billerCode, BillerContract contract) implements HttpBased {}
    record WalletRailProtocol(String walletNetwork, HmacRef hmac) implements HttpBased {}
    record CustodianRestProtocol(String custodianCode, CertificateRef mtls) implements HttpBased {}

    /** Transport endpoint of a session-bound host. */
    record HostProfile(String host, int port, boolean tls) {}

    /** Name of the env var holding a client certificate (or its key) — never the secret itself. */
    record CertificateRef(String envRef) {}

    /** Name of the env var holding a shared secret — never the secret itself. */
    record SharedSecretRef(String envRef) {}

    /** Name of the env var holding an HMAC secret — never the secret itself. */
    record HmacRef(String envRef) {}

    /** Batch file exchange profile (SFTP in/out-baskets). */
    record FtpProfile(String host, String credentialEnvRef, String outBasket, String inBasket) {}

    /** Per-biller contract clause (plan §12.3): reversal class A|B|C + contractual window where B. */
    record BillerContract(String billerCode, ReversalClass reversalClass, Duration contractReversalWindow) {}
}
