package com.fsp.shared.rails;

import java.time.Duration;

/**
 * How bytes reach the counterparty (plan §3.4.1) — orthogonal to the message format.
 * Adding a transport touches nothing else. A SFTP in-basket cadence miss is a RECON
 * WARNING, never a breaker event (a late statement is not a rail outage). Raw message
 * text never survives parsing past this seam (no secrets, no payloads in logs).
 */
public sealed interface TransportChannel {

    record MtlsHttpChannel(String endpoint, ProviderProtocol.CertificateRef clientCert) implements TransportChannel {}

    /** HTTPS inside the bank VPN — a shared identity, never a bearer token. */
    record VpnHttpChannel(String endpoint, ProviderProtocol.SharedSecretRef sharedRef) implements TransportChannel {}

    /** ISO 8583 / terminal sockets — session lifecycle owned by the adapter (logon/echo/logoff). */
    record TcpSessionChannel(ProviderProtocol.HostProfile host, Duration sessionTimeout) implements TransportChannel {}

    /** Batch file exchange: out-basket submissions, in-basket statement pickups. */
    record SftpChannel(ProviderProtocol.FtpProfile profile) implements TransportChannel {}

    /** IBM MQ request/response queues. */
    record MqChannel(String queueManager, String reqQueue, String respQueue) implements TransportChannel {}
}
