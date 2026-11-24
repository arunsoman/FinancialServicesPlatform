package com.fsp.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-client (browser) request rate limiter enforced at the gateway edge.
 *
 * <p>FAPI 2.0 note: the FAPI 2.0 Security Profile does NOT prescribe a minimum
 * spacing between browser requests. What FAPI 2.0 / RFC 9449 requires is
 * replay prevention of individual DPoP proofs via the {@code jti} claim
 * (validated in {@link ForwardFilterConfig.ForwardFilter#validateDPoPProofInternal})
 * and single-use authorization codes. Rate limiting rapid-fire browser requests is
 * <b>defense-in-depth</b> (PSD2 Art. 95 security measures; DORA ICT risk
 * management): it protects token validation and authorization decisions from
 * burst abuse, not a normative FAPI 2.0 spacing rule.
 *
 * <p>Each client is keyed by a hash of its session cookie (when present — the
 * browser only ever holds the BFF session cookie, tokens never enter the browser)
 * falling back to the client IP. Raw cookie/IP are never logged or stored.
 * In-memory per node (single gateway node in the demo topology); a cluster
 * deployment swaps the map for a shared store (see {@code com.fsp.idp.LoginThrottle}).
 *
 * <p>Thread-safety: per-client {@link ConcurrentLinkedDeque} is lock-safe under
 * burst; no synchronized blocks, no DB/JVM locks across I/O.
 */
@Component
public class BrowserRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(BrowserRateLimiter.class);

    private final Clock clock;
    private final boolean enabled;
    private final int maxRequests;
    private final Duration window;
    /** clientKeyHash (SHA-256 b64 of cookie/IP) → timestamps of recent requests. */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> byClient =
            new ConcurrentHashMap<>();

    public BrowserRateLimiter(Clock clock, GatewayProperties props) {
        this.clock = clock;
        GatewayProperties.RateLimit rl = props.getRateLimit();
        this.enabled = rl.enabled();
        this.maxRequests = rl.maxRequests();
        this.window = rl.window();
    }

    /** Result of a rate-limit check. */
    public record Verdict(boolean allowed, Duration retryAfter) {}

    /**
     * Check and record one request for {@code clientKeyHash}.
     *
     * @return a verdict; {@code allowed=false} with a {@link Duration#isNegative non-negative}
     *         {@code retryAfter} when the client is over its window.
     */
    public Verdict check(String clientKeyHash) {
        if (!enabled) {
            return new Verdict(true, null);
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        ConcurrentLinkedDeque<Instant> q = byClient.computeIfAbsent(clientKeyHash, k -> new ConcurrentLinkedDeque<>());
        // Drop entries that fell out of the sliding window.
        Instant first;
        while ((first = q.peekFirst()) != null && first.isBefore(cutoff)) {
            q.pollFirst();
        }
        if (q.size() >= maxRequests) {
            Instant oldest = q.peekFirst();
            Duration retryAfter = oldest == null
                    ? Duration.ofMillis(1)
                    : Duration.between(now, oldest.plus(window));
            if (retryAfter.isNegative() || retryAfter.isZero()) {
                retryAfter = Duration.ofMillis(1);
            }
            log.warn("gateway_rate_limited clientHashed={} maxRequests={} windowMs={} retryAfterMs={}",
                Integer.toHexString(clientKeyHash.hashCode()), maxRequests, window.toMillis(),
                retryAfter.toMillis());
            return new Verdict(false, retryAfter);
        }
        q.addLast(now);
        return new Verdict(true, null);
    }

    /** Drop idle client buckets so the map cannot grow unboundedly. */
    @Scheduled(fixedDelay = 60_000)
    void prune() {
        Instant cutoff = clock.instant().minus(window);
        byClient.entrySet().removeIf(entry -> {
            ConcurrentLinkedDeque<Instant> q = entry.getValue();
            Instant first;
            while ((first = q.peekFirst()) != null && first.isBefore(cutoff)) {
                q.pollFirst();
            }
            return q.isEmpty();
        });
    }
}
