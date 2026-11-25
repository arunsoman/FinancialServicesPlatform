package com.fsp.bff;

import com.fsp.bff.BffDtos.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * GET /bff/ops/auth — nginx `auth_request` for the ops console (Grafana at /ops/, served on the
 * BFF's own host). FAPI 2.0: Grafana never talks to the IdP and never sees a token — the BFF owns
 * the session (PAR + PKCE + DPoP login) and only tells nginx WHO is signed in; nginx passes that
 * to Grafana's auth proxy as a header it overwrites on every request.
 *
 *   204 + X-FSP-Ops-User / X-FSP-Ops-Name   a live session of a PLATFORM-tenant person
 *   401                                      no live session (nginx redirects to /bff/auth/login)
 *   403                                      signed in, but not platform staff
 *
 * Anything a person DOES in the console (register, approve, suspend) is an ordinary /api call
 * with the session cookie + CSRF, decided by core (sole PDP) with four-eyes at person_id.
 */
@Slf4j
@RestController
public class BffOpsController {

    static final String PLATFORM = "PLATFORM";
    private final SessionTokens tokens;

    public BffOpsController(SessionTokens tokens) {
        this.tokens = tokens;
    }

    @GetMapping("/bff/ops/auth")
    public ResponseEntity<Void> auth(HttpServletRequest request) {
        SessionUser user = BffSessionController.current(request);
        HttpSession session = request.getSession(false);
        if (user == null || session == null || !Instant.now().isBefore(tokens.absoluteEndInstant(session))) {
            return ResponseEntity.status(401).build();
        }
        if (!PLATFORM.equals(user.tenantType()) || user.applicant() || !safe(user.username())) {
            log.warn("bff_ops_refused user={} tenantType={}", user.username(), user.tenantType());
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.noContent()
            .header("X-FSP-Ops-User", user.username())
            .header("X-FSP-Ops-Name", headerSafe(user.fullName() == null ? user.username() : user.fullName()))
            .header("Cache-Control", "no-store")
            .build();
    }

    /** Grafana logins are created from this value — keep it to a plain login name. */
    static boolean safe(String username) {
        return username != null && username.matches("[A-Za-z0-9._@-]{1,64}");
    }

    static String headerSafe(String v) {
        String s = v.replaceAll("[^\\p{L}\\p{N} .,'_-]", "").strip();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
