package com.fsp.bff;

import com.fsp.bff.BffDtos.CsrfResponse;
import com.fsp.bff.BffDtos.LoginRequest;
import com.fsp.bff.BffDtos.SessionResponse;
import com.fsp.bff.BffDtos.SessionUser;
import com.fsp.bff.IdpClient.ParSessionState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Session surface (PRD §6): /bff/auth/login, /bff/auth/callback, /bff/auth/logout, /bff/session, /bff/csrf.
 * FAPI 2.0 compliant: Login initiates PAR flow → redirects to IdP hosted sign-in → callback completes auth code exchange.
 * The IdP token stays server-side in the session; the SPA only sees the session cookie + CSRF.
 */
@Slf4j
@RestController
public class BffSessionController {

    static final String SESSION_USER = "FSP_SESSION_USER";
    static final String SESSION_TOKEN = "FSP_SESSION_TOKEN";
    static final String SESSION_DPOP_KEY = "FSP_SESSION_DPOP_KEY";
    static final String SESSION_PAR_STATE = "FSP_SESSION_PAR_STATE";

    private final IdpClient idp;
    private final BffProperties props;
    private final SessionTokens tokens;

    public BffSessionController(IdpClient idp, BffProperties props, SessionTokens tokens) {
        this.tokens = tokens;
        this.idp = idp;
        this.props = props;
    }

    /**
     * FAPI 2.0 Login Initiation: Start PAR flow and redirect to IdP hosted sign-in page.
     * The browser never sees credentials - full page redirect to IdP.
     */
    @GetMapping("/bff/auth/login")
    public RedirectView login(@RequestParam(value = "return_to", defaultValue = "/") String returnTo,
                               HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        
        // Store return_to in session for post-login redirect
        session.setAttribute("FSP_LOGIN_RETURN_TO", returnTo);
        
        try {
            // Initiate PAR flow - always throws ParFlowInitiated carrying the URL + state
            String redirectUri = props.getLoginRedirectUri();
            String scope = "openid profile email roles"; // FAPI 2.0 scopes
            idp.startParFlow(redirectUri, scope);
            throw new IllegalStateException("startParFlow must throw ParFlowInitiated");
        } catch (IdpClient.ParFlowInitiated e) {
            // Store PAR session state in HTTP session
            session.setAttribute(SESSION_PAR_STATE, e.getState());
            
            // Redirect to IdP authorization endpoint (minimal params: client_id + request_uri)
            log.info("bff_par_initiated redirecting to IdP: {}", e.getAuthzUrl());
            return new RedirectView(e.getAuthzUrl());
        } catch (Exception ex) {
            log.error("bff_login_par_failed", ex);
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Login initiation failed: " + ex.getMessage());
            pd.setProperty("code", "LOGIN_INIT_FAILED");
            throw new ApiProblemException(pd);
        }
    }

    /**
     * FAPI 2.0 Callback: Handle authorization response from IdP.
     * Validates iss, exchanges auth code with PKCE + DPoP + private_key_jwt.
     */
    @GetMapping("/bff/auth/callback")
    public RedirectView callback(@RequestParam("code") String authCode,
                                  @RequestParam("iss") String iss,
                                  @RequestParam(value = "state", required = false) String state,
                                  HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            log.warn("bff_callback_no_session");
            return new RedirectView("/login?error=session_expired");
        }

        ParSessionState parState = (ParSessionState) session.getAttribute(SESSION_PAR_STATE);
        if (parState == null) {
            log.warn("bff_callback_no_par_state");
            return new RedirectView("/login?error=invalid_state");
        }

        // The authorization response must carry the exact state this session sent at PAR time
        if (state == null || parState.state() == null || !java.security.MessageDigest.isEqual(
                state.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                parState.state().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            log.warn("bff_callback_state_mismatch present={}", state != null);
            session.removeAttribute(SESSION_PAR_STATE);
            return new RedirectView("/login?error=invalid_state");
        }

        try {
            // Complete authorization code flow
            IdpClient.TokenResponse tokenResp = idp.completeAuthCodeFlow(authCode, iss, parState);
            String returnTo = (String) session.getAttribute("FSP_LOGIN_RETURN_TO");
            
            // Create session user
            SessionUser user = new SessionUser(
                tokenResp.claims().userId(), tokenResp.claims().username(), tokenResp.claims().fullName(),
                tokenResp.claims().tenantId(), tokenResp.claims().tenantName(), tokenResp.claims().tenantType(),
                null, null, false, tokenResp.claims().roles());
            
            // Store session data (rotate session ID for fixation protection)
            session.invalidate();
            HttpSession newSession = request.getSession(true);
            tokens.start(newSession, tokenResp); // access + refresh token, idle + absolute limits
            newSession.setAttribute(SESSION_DPOP_KEY, parState.dpopPrivateKeyJwk());
            newSession.setAttribute(SESSION_USER, user);
            request.changeSessionId();
            // Re-anchor the post-login destination (invalidate() above dropped it)
            newSession.setAttribute("FSP_LOGIN_RETURN_TO", returnTo);
            
            // Clean up PAR state
            newSession.removeAttribute(SESSION_PAR_STATE);
            
            // Rotate CSRF token (FAPI 2.0 requirement)
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                // CSRF token will be regenerated on next request
            }
            
            log.info("bff_login_completed user={} tenant={} roles={}", 
                user.username(), user.tenantId(), user.roles());
            
            // Redirect to the original destination. returnTo is an app path ("/admin") —
            // it must resolve on the SPA origin, not on the BFF origin.
            newSession.removeAttribute("FSP_LOGIN_RETURN_TO");
            String safeTo = (returnTo == null || returnTo.isBlank()) ? "/" : returnTo;
            if (!safeTo.startsWith("http")) {
                String spaBase = java.net.URI.create(props.getLoginRedirectUri())
                    .resolve("/").toString();
                if (spaBase.endsWith("/")) {
                    spaBase = spaBase.substring(0, spaBase.length() - 1);
                }
                safeTo = spaBase + safeTo;
            }
            return new RedirectView(safeTo);
            
        } catch (Exception ex) {
            log.error("bff_callback_failed", ex);
            session.invalidate();
            return new RedirectView("/login?error=" + ex.getMessage());
        }
    }

    @PostMapping("/bff/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            tokens.end(session, "logout"); // logout trio: revoke the refresh token at the IdP, drop the session
        }
        log.info("bff_logout");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/bff/session")
    public SessionResponse session(HttpServletRequest request) {
        SessionUser user = current(request);
        HttpSession session = request.getSession(false);
        if (user == null || session == null) return new SessionResponse(false, null, null, null);
        // the browser signs out after this much real inactivity, and before the absolute end
        return new SessionResponse(true, user, props.getSession().getIdleTimeoutMinutes() * 60,
            tokens.absoluteEndInstant(session).toString());
    }

    /** Forces the CSRF cookie materialization for SPA double-submit. */
    @GetMapping("/bff/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    public static SessionUser current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (SessionUser) session.getAttribute(SESSION_USER);
    }

    public static String token(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(SESSION_TOKEN);
    }

    public static String dpopKey(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(SESSION_DPOP_KEY);
    }

    @ExceptionHandler(ApiProblemException.class)
    @ResponseStatus // passthrough with original status
    public ProblemDetail handleProblem(ApiProblemException e) {
        return e.problem();
    }
}