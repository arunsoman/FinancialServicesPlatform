package com.fsp.bff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Keeps a BFF session's tokens alive while the person works, and ends the session when it must
 * (FAPI 2.0 BFF: the browser never holds a token — only the session cookie).
 *
 * <ul>
 *   <li><b>Renew while active</b> — before a proxied call, an access token with less than
 *       {@code refreshSkewSeconds} left is renewed with the session's refresh token
 *       (private_key_jwt + DPoP by the session's key; the new token stays bound to that key).</li>
 *   <li><b>Idle</b> — the servlet session expires after {@code idleTimeoutMinutes} without any
 *       request (a closed tab). The browser signs out earlier on real user inactivity.</li>
 *   <li><b>Absolute</b> — {@code absoluteTimeoutMinutes} after sign-in the session ends whatever
 *       the activity; the refresh token is revoked at the IdP (RFC 7009).</li>
 * </ul>
 * No lock is held around the IdP call: refresh tokens are not rotated, so two concurrent renewals
 * only mint two valid access tokens (the later one is kept). Tokens are never logged.
 */
@Component
public class SessionTokens {

    private static final Logger log = LoggerFactory.getLogger(SessionTokens.class);

    static final String REFRESH = "FSP_SESSION_REFRESH";
    static final String TOKEN_EXP = "FSP_SESSION_TOKEN_EXP";
    static final String LOGIN_AT = "FSP_SESSION_LOGIN_AT";

    private final IdpClient idp;
    private final BffProperties props;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public SessionTokens(IdpClient idp, BffProperties props) {
        this(idp, props, Clock.systemUTC());
    }

    SessionTokens(IdpClient idp, BffProperties props, Clock clock) {
        this.idp = idp;
        this.props = props;
        this.clock = clock;
    }

    /** At sign-in: remember the refresh token, when the access token expires and when the session began. */
    public void start(HttpSession session, IdpClient.TokenResponse t) {
        long now = clock.instant().getEpochSecond();
        session.setAttribute(BffSessionController.SESSION_TOKEN, t.accessToken());
        session.setAttribute(REFRESH, t.refreshToken());
        session.setAttribute(TOKEN_EXP, now + t.expiresIn());
        session.setAttribute(LOGIN_AT, now);
        session.setMaxInactiveInterval(props.getSession().getIdleTimeoutMinutes() * 60);
    }

    /**
     * A live access token for this request, renewed if it is about to expire. Null when there is
     * no session or it just ended (absolute timeout, or the IdP refused the renewal) — the caller
     * answers 401 so the browser goes back to sign-in.
     */
    public String liveToken(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        String token = (String) session.getAttribute(BffSessionController.SESSION_TOKEN);
        if (token == null) return null;
        long now = clock.instant().getEpochSecond();
        if (now >= absoluteEnd(session)) {
            end(session, "absolute_timeout");
            return null;
        }
        Long exp = (Long) session.getAttribute(TOKEN_EXP);
        if (exp == null || now < exp - props.getSession().getRefreshSkewSeconds()) return token;

        String refresh = (String) session.getAttribute(REFRESH);
        String dpopKey = (String) session.getAttribute(BffSessionController.SESSION_DPOP_KEY);
        if (refresh == null || dpopKey == null) {
            // a session from before refresh tokens: keep the token until it really expires
            return now < exp ? token : endAndNull(session, "no_refresh_token");
        }
        IdpClient.TokenResponse renewed;
        try {
            renewed = idp.refresh(refresh, dpopKey);
        } catch (RuntimeException e) {
            // IdP unreachable: the current token may still have a few seconds; never end the session for a blip
            log.warn("bff_token_refresh_unavailable error={}", e.getClass().getSimpleName());
            return now < exp ? token : null;
        }
        if (renewed == null) return endAndNull(session, "refresh_refused");
        session.setAttribute(BffSessionController.SESSION_TOKEN, renewed.accessToken());
        session.setAttribute(TOKEN_EXP, now + renewed.expiresIn());
        log.info("bff_token_refreshed expiresIn={}", renewed.expiresIn());
        return renewed.accessToken();
    }

    /** Epoch second at which this session ends regardless of activity. */
    public long absoluteEnd(HttpSession session) {
        Long loginAt = (Long) session.getAttribute(LOGIN_AT);
        long start = loginAt != null ? loginAt : session.getCreationTime() / 1000;
        return start + props.getSession().getAbsoluteTimeoutMinutes() * 60L;
    }

    /** End the session: revoke its refresh token at the IdP (logout trio), then drop the session. */
    public void end(HttpSession session, String reason) {
        String refresh = null;
        try {
            refresh = (String) session.getAttribute(REFRESH);
        } catch (IllegalStateException alreadyInvalid) {
            return;
        }
        idp.revoke(refresh, reason);
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // already invalidated by a concurrent request
        }
        log.info("bff_session_ended reason={}", reason);
    }

    private String endAndNull(HttpSession session, String reason) {
        end(session, reason);
        return null;
    }

    /** Instant form for the session endpoint. */
    public Instant absoluteEndInstant(HttpSession session) {
        return Instant.ofEpochSecond(absoluteEnd(session));
    }
}
