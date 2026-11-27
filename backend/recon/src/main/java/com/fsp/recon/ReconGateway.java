package com.fsp.recon;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * Honest read-only probes over the internal plane (chapter 13 §8): recon-worker fetches
 * the {@code /internal/recon/snapshot} faces that each book-owner service exposes behind
 * the internal-credential hop. Timeouts follow chapter 13 §5 (connect 2 s / read 8 s);
 * any transport failure is a probe failure, never an invariant breach.
 */
@Component
public class ReconGateway {

    private final ReconProperties props;

    public ReconGateway(ReconProperties props) {
        this.props = props;
    }

    private RestClient clientFor(String baseUrl, String credential) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(8).toMillis());
        return RestClient.builder().baseUrl(baseUrl)
            .requestFactory(f)
            .defaultHeader("X-FSP-Internal-Credential", credential == null ? "" : credential)
            .build();
    }

    /** Never throws — transport failures return empty (probe failed, not proven break). */
    public java.util.Optional<ReconFeeds.PspSnapshot> fetchPspSnapshot() {
        try {
            return java.util.Optional.ofNullable(
                clientFor(props.getPspBaseUrl(), props.getPspInternalCredential()).get()
                    .uri("/internal/recon/snapshot")
                    .retrieve().body(ReconFeeds.PspSnapshot.class));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    /** Never throws — transport failures return empty (probe failed, not proven break). */
    public java.util.Optional<ReconFeeds.EmiSnapshot> fetchEmiSnapshot() {
        try {
            return java.util.Optional.ofNullable(
                clientFor(props.getEmiBaseUrl(), props.getEmiInternalCredential()).get()
                    .uri("/internal/recon/snapshot")
                    .retrieve().body(ReconFeeds.EmiSnapshot.class));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Machine-plane page into core's notification plane (V29/V31 channel dispatch). The
     * idempotency key is deterministic (violation key + dispatch window, core §2e): core
     * replays the same page id instead of duplicating recipients across worker retries.
     */
    public boolean page(String invariant, String severity, String detail) {
        try {
            clientFor(props.getCoreBaseUrl(), props.getCoreInternalCredential()).post()
                .uri("/v1/recon/pages")
                .header("X-FSP-Actor-Type", "MACHINE")
                .header("X-FSP-Client-Id", "recon-worker")
                .header("X-FSP-Tenant-Id", props.getPlatformTenantId())
                .header("X-FSP-Username", "recon-worker")
                .header("Idempotency-Key", idempotencyKey(invariant, severity))
                .body(java.util.Map.of("invariant", invariant, "severity", severity,
                    "detail", detail))
                .retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Page dispatch window (UTC day) — retries inside a window carry the same key. */
    static String dispatchWindow() {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }

    /** Core §2e charset (A-Za-z0-9._-): sanitize invariant parts; scoped per dispatch day. */
    static String idempotencyKey(String invariant, String severity) {
        return ("recon-" + invariant + "-" + severity + "-" + dispatchWindow())
            .replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
