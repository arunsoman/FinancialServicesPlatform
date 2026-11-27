package com.fsp.idp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login brute-force throttle (red-team round 2, wave 10e): N consecutive failed password
 * entries for one username lock that username for a cool-off window. In-memory, per-node —
 * acceptable for the demo topology (single IdP node); a cluster deployment swaps this for
 * a shared store. Successful sign-ins clear the counter. Never leaks whether a username
 * exists: both unknown and known usernames count the same (uniform enumeration defense).
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    static final int MAX_FAILURES = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration LOCKOUT = Duration.ofMinutes(5);

    private record State(java.util.ArrayDeque<Instant> failures, Instant lockedUntil) {}

    private final Map<String, State> byUsername = new ConcurrentHashMap<>();

    /** @throws IdpProblemException 429 when the username is locked out. */
    void check(String username) {
        State s = byUsername.get(key(username));
        if (s != null && s.lockedUntil() != null && Instant.now().isBefore(s.lockedUntil())) {
            log.warn("idp_login_locked username_hashed={}",
                Integer.toHexString(key(username).hashCode()));
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "LOGIN_LOCKED", "Too many failed attempts; try again later", 429));
        }
    }

    /** Record a failed credential entry for this username. */
    void failure(String username) {
        String k = key(username);
        Instant now = Instant.now();
        byUsername.compute(k, (ignored, s) -> {
            java.util.ArrayDeque<Instant> q = (s != null) ? s.failures() : new java.util.ArrayDeque<>();
            q.addLast(now);
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(FAILURE_WINDOW))) q.removeFirst();
            Instant lock = q.size() >= MAX_FAILURES ? now.plus(LOCKOUT) : (s != null ? s.lockedUntil() : null);
            if (lock != null) {
                log.warn("idp_login_lockout_applied key_hashed={} failures={}",
                    Integer.toHexString(k.hashCode()), q.size());
            }
            return new State(q, lock);
        });
    }

    /** Clear the counter on successful sign-in. */
    void success(String username) {
        byUsername.remove(key(username));
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
