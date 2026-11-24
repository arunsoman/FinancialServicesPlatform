package com.fsp.idp;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * IdP surface: token issuance (public, credential-authenticated), JWKS (public), introspection
 * (public), tenant IdP policy (internal hop via gateway). The native IdP is the DEFAULT provider;
 * tenant admins flip to THIRD_PARTY here.
 * FAPI 2.0 endpoints: PAR (/par), Authorization (/authorize), Token (/token with auth_code).
 */
@RestController
@RequestMapping("/idp")
public class IdpController {

    private static final Logger log = LoggerFactory.getLogger(IdpController.class);

    /** Closed set of reasons the hosted error page will render; anything else shows as unknown_error. */
    private static final java.util.Set<String> ERROR_CODES = java.util.Set.of(
        "request_uri_invalid", "authentication_failed", "account_inactive", "tenant_inactive",
        "federation_pending", "server_error", "unknown_error", "activation_pending");

    private final IdpTokenService tokenService;
    private final ParService parService;
    private final IdpAuthService auth;
    private final IdpProperties props;
    private final JdbcTemplate jdbc;
    private final PPersonRepository persons;
    private final IdpActivationService activation;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bcrypt =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    public IdpController(IdpTokenService tokenService, ParService parService, IdpAuthService auth,
                         IdpProperties props, JdbcTemplate jdbc, PPersonRepository persons,
                         IdpActivationService activation) {
        this.activation = activation;
        this.tokenService = tokenService;
        this.parService = parService;
        this.auth = auth;
        this.jdbc = jdbc;
        this.props = props;
        this.persons = persons;
    }

    // ========== FAPI 2.0: Pushed Authorization Request (PAR) ==========

    @PostMapping(value = "/par", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public IdpDtos.ParResponse pushedAuthzRequest(@RequestBody Map<String, String> body,
                                                   @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Snake_case wire params (RFC 9126 §2.1); client_assertion sent as Bearer (private_key_jwt)
        String clientAssertion = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            clientAssertion = authHeader.substring(7).trim();
        }
        IdpDtos.ParRequest request = new IdpDtos.ParRequest(
            body.get("grant_type"), body.get("code_challenge"), body.get("code_challenge_method"),
            body.get("redirect_uri"), body.get("response_type"), body.get("scope"),
            body.get("authorization_details"), body.get("resource"),
            body.get("client_assertion"), body.get("client_assertion_type"), body.get("state"));
        if (clientAssertion == null) {
            clientAssertion = body.get("client_assertion");
        }
        return parService.createParRequest(request, clientAssertion);
    }

    // ========== Hosted forms: one press = one submission ==========

    /**
     * Every hosted form consumes a single-use artefact (PAR request_uri, MFA / password-change
     * ticket, activation token). A double-click would POST twice; the browser abandons the first
     * navigation and renders the second answer — request_uri_invalid / ticket expired — even
     * though the first sign-in succeeded. CSP forbids inline script (default-src 'self'), so
     * the guard is a same-origin file, not an inline handler.
     */
    static final String SUBMIT_ONCE_TAG = "<script src=\"/idp/submit-once.js\" defer></script>";

    static final String SUBMIT_ONCE_JS = """
        (function () {
          'use strict';
          document.addEventListener('submit', function (e) {
            var f = e.target;
            if (!(f instanceof HTMLFormElement)) return;
            if (f.getAttribute('data-submitted') === '1') { e.preventDefault(); return; }
            f.setAttribute('data-submitted', '1');
            // disable only the buttons: disabled inputs would drop out of the POST body
            f.querySelectorAll('button, input[type=submit]').forEach(function (b) {
              b.disabled = true;
              b.setAttribute('aria-busy', 'true');
              b.style.opacity = '0.6';
              b.style.cursor = 'progress';
            });
          }, true);
          // Back/forward cache restores the page as it was left: let the user sign in again
          window.addEventListener('pageshow', function (e) {
            if (!e.persisted) return;
            document.querySelectorAll('form[data-submitted]').forEach(function (f) {
              f.removeAttribute('data-submitted');
              f.querySelectorAll('button, input[type=submit]').forEach(function (b) {
                b.disabled = false;
                b.removeAttribute('aria-busy');
                b.style.opacity = '';
                b.style.cursor = '';
              });
            });
          });
          // ---- Show-password toggles: CSP-safe (no inline handlers), markup-driven ----
          document.querySelectorAll('button[data-pw-toggle]').forEach(function (btn) {
            var input = document.getElementById(btn.getAttribute('data-pw-toggle'));
            if (!input || !(input instanceof HTMLInputElement)) return;
            btn.addEventListener('click', function () {
              var show = input.getAttribute('type') === 'password';
              input.setAttribute('type', show ? 'text' : 'password');
              btn.textContent = show ? 'Hide' : 'Show';
              btn.setAttribute('aria-pressed', String(show));
              btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
              input.focus({ preventScroll: true });
              try { input.setSelectionRange(input.value.length, input.value.length); } catch (ignored) { }
            });
          });
        })();
        """;

    @GetMapping(value = "/submit-once.js", produces = "text/javascript")
    public org.springframework.http.ResponseEntity<String> submitOnceJs() {
        return org.springframework.http.ResponseEntity.ok()
            .header("Cache-Control", "public, max-age=3600")
            .header("X-Content-Type-Options", "nosniff")
            .body(SUBMIT_ONCE_JS);
    }

    /** Simulated error surface for the hosted (Phase-0) sign-in flows. */
    @GetMapping(value = "/error", produces = MediaType.TEXT_HTML_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public String errorPage(@RequestParam(value = "code", required = false) String code) {
        // Only allow-listed codes are rendered: the query string is attacker-controlled
        String safe = code != null && ERROR_CODES.contains(code) ? code : "unknown_error";
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <title>FSP Identity Provider &middot; Error</title>
            <style>body { font-family: system-ui, sans-serif; background: #101418; color: #e8edf2;
                   display: grid; place-items: center; min-height: 100dvh; margin: 0; }</style>
            </head><body><div style="text-align:center">
            <p style="font-size:.7rem;letter-spacing:.12em;text-transform:uppercase;color:#f0a10e">FSP Identity Provider</p>
            <h1 style="font-weight:600">Sign-in could not continue</h1>
            <p style="color:#9fb0c0">Reason: %s &mdash; restart sign-in from your portal.</p>
            </div></body></html>
            """.formatted(safe);
    }

    // ========== FAPI 2.0: Authorization Endpoint ==========

    @GetMapping(value = "/authorize")
    public RedirectView authorize(@RequestParam("client_id") String clientId,
                                   @RequestParam("request_uri") String requestUri) {
        // 1. Peek PAR request (validates expiry; consumed at callback completion)
        ParService.ParRequestData parData = parService.peekParRequest(requestUri);
        if (parData == null) {
            // Invalid/expired/used request_uri - redirect to error page
            return new RedirectView("/idp/error?code=request_uri_invalid");
        }

        // 2. Store PAR data in session or pass via request attribute for the login page
        // In a real implementation, this would render a hosted sign-in page
        // For now, we simulate by redirecting to a login page with the PAR data encoded
        // The login page would authenticate the user and then call back to /authorize/callback
        
        // Build the login page URL with PAR context
        String loginUrl = "/idp/login?request_uri=" + java.net.URLEncoder.encode(requestUri, java.nio.charset.StandardCharsets.UTF_8)
            + "&client_id=" + java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8);
        
        return new RedirectView(loginUrl);
    }

    /** Simulated hosted sign-in page (Phase-0): the SCA surface lives at the IdP, never in BFF/SPA. */
    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public String loginPage(@RequestParam("request_uri") String requestUri,
                            @RequestParam("client_id") String clientId,
                            jakarta.servlet.http.HttpServletResponse response) {
        allowFormRedirectTo(response, parService.peekParRequest(requestUri));
        // Raw URI, HTML-escaped for the attribute; the browser URL-encodes it on form submit
        String enc = html(requestUri);
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>FSP Identity Provider &middot; Sign in</title>
            <style>
              body { font-family: system-ui, sans-serif; background: #101418; color: #e8edf2;
                     display: grid; place-items: center; min-height: 100dvh; margin: 0; }
              .panel { background: #1a2029; border: 1px solid #2a323d; border-radius: 12px;
                       padding: 2rem; width: min(92vw, 22rem); }
              h1 { font-size: 1.05rem; margin: 0 0 .25rem; }
              p.eyebrow { font-size: .7rem; letter-spacing: .12em; text-transform: uppercase;
                          color: #f0a10e; margin: 0 0 .75rem; }
              label { display: block; font-size: .75rem; margin: .75rem 0 .25rem; color: #9fb0c0; }
              input { width: 100%%; box-sizing: border-box; padding: .55rem .7rem; border-radius: 8px;
                      border: 1px solid #3a4450; background: #101418; color: #e8edf2; font: inherit; }
              button { margin-top: 1.25rem; width: 100%%; padding: .6rem; border: 0; border-radius: 8px;
                       background: #f0a10e; color: #101418; font-weight: 700; cursor: pointer; }
              .hint { margin-top: 1rem; font-size: .7rem; color: #7a8a99; line-height: 1.5; }
              .pw-row { display: flex; gap: .4rem; align-items: stretch; }
              .pw-row input { width: auto; flex: 1 1 auto; min-width: 0; }
              .pw-toggle { margin-top: 0; width: auto; flex: 0 0 auto; padding: .55rem .7rem;
                           border: 1px solid #3a4450; border-radius: 8px; background: transparent;
                           color: #9fb0c0; font: inherit; font-size: .7rem; cursor: pointer; }
              .pw-toggle:hover { border-color: #f0a10e; color: #f0a10e; }
            </style></head><body><div class="panel">
            <p class="eyebrow">FSP Identity Provider</p>
            <h1>Sign in to continue</h1>
            <form method="post" action="/idp/authorize/callback">
              <input type="hidden" name="request_uri" value="%s">
              <label for="u">Username</label>
              <input id="u" name="username" autocomplete="username" required autofocus>
              <label for="p">Password</label>
              <div class="pw-row">
                <input id="p" name="password" type="password" autocomplete="current-password" required>
                <button type="button" class="pw-toggle" data-pw-toggle="p"
                        aria-pressed="false" aria-label="Show password">Show</button>
              </div>
              <button type="submit">Sign in</button>
            </form>
            <p class="hint">Client: %s &middot; Your credentials never leave the Identity Provider.</p>
            </div></body></html>
            """.formatted(enc, html(clientId)).replace("</head>", SUBMIT_ONCE_TAG + "</head>");
    }

    // ========== FAPI 2.0: Authorization Callback (after user consent) ==========

    // POST only: credentials travel in the form body, never in the URL (history, logs, Referer)
    @PostMapping(value = "/authorize/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Object authorizeCallback(@RequestParam("request_uri") String requestUri,
                                           @RequestParam("username") String username,
                                           @RequestParam("password") String password,
                                           jakarta.servlet.http.HttpServletResponse response) {
        // In a real implementation, this would be after the user authenticates at the hosted sign-in page
        // For Phase-0, we simulate by accepting username/password here (but this should be a full-page IdP form)
        
        // 1. Consume PAR request
        ParService.ParRequestData parData = parService.consumeParRequest(requestUri);
        if (parData == null) {
            log.warn("idp_authorize_failed reason=request_uri_invalid");
            return new RedirectView("/idp/error?code=request_uri_invalid");
        }
        // The password-change / MFA steps rendered below post here, then 302 to the client
        allowFormRedirectTo(response, parData);

        // 2. Authenticate user (in real flow, this happens at IdP hosted page)
        // For now, delegate to token service password grant to validate credentials
        IdpDtos.TokenGrant grant = new IdpDtos.TokenGrant("password", username, password, parData.clientId(), null);
        try {
            IdpDtos.TokenResponse tokenResp = tokenService.grantPassword(grant);
            
            // 3. Store auth code linked to this user
            String authCode = parService.storeAuthCode(parData, 
                UUID.fromString(tokenResp.claims().userId()), 
                UUID.fromString(tokenResp.claims().tenantId()));
            
            // 4. Redirect back to client's redirect_uri with code + the client's OWN state + iss
            StringBuilder redirectUri = new StringBuilder(parData.redirectUri())
                .append("?code=").append(URLEncoder.encode(authCode, StandardCharsets.UTF_8));
            if (parData.state() != null) {
                redirectUri.append("&state=").append(URLEncoder.encode(parData.state(), StandardCharsets.UTF_8));
            }
            redirectUri.append("&iss=").append(URLEncoder.encode(props.getIssuer(), StandardCharsets.UTF_8));
            log.info("idp_authorize_succeeded clientId={} username={}", parData.clientId(), username);
            return new RedirectView(redirectUri.toString());
        } catch (IdpProblemException e) {
            // F-8: first sign-in with the bootstrap password → the person sets their own
            // password AT the IdP (hosted surface), credentials never touch BFF/SPA.
            if ("PASSWORD_CHANGE_REQUIRED".equals(e.problem().code())) {
                PPerson person = persons.findByUsername(username).orElse(null);
                if (person != null) {
                    String ticket = parService.storePasswordChangeTicket(username, person.id, person.tenantId, parData);
                    log.info("idp_password_change_page username={} clientId={}", username, parData.clientId());
                    return changePage(ticket, "");
                }
            }
            // V30 MFA: password verified, verification code already dispatched — park the PAR
            // in an OTP-sized ticket and render the code step at the IdP itself.
            if ("MFA_REQUIRED".equals(e.problem().code())) {
                PPerson person = persons.findByUsername(username).orElse(null);
                if (person != null) {
                    String ticket = parService.storeMfaTicket(username, person.id, person.tenantId, parData);
                    log.info("idp_mfa_page username={} clientId={}", username, parData.clientId());
                    return mfaPage(ticket, "");
                }
            }
            // Business rejection (grantPassword already logged the credential detail); map to a page code
            String code = switch (e.problem().code()) {
                case "INVALID_CREDENTIALS", "INVALID_REQUEST" -> "authentication_failed";
                case "PERSON_INACTIVE" -> "account_inactive";
                case "ACTIVATION_PENDING" -> "activation_pending";
                case "MFA_CODE_INVALID", "MFA_CODE_EXPIRED", "MFA_ATTEMPTS_EXHAUSTED" -> "authentication_failed";
                case "TENANT_INACTIVE", "TENANT_MISSING" -> "tenant_inactive";
                case "IDP_EXTERNAL_FEDERATION_PENDING" -> "federation_pending";
                default -> "authentication_failed";
            };
            log.warn("idp_authorize_failed clientId={} username={} problem={} status={}",
                parData.clientId(), username, e.problem().code(), e.problem().status());
            return new RedirectView("/idp/error?code=" + code);
        } catch (RuntimeException e) {
            log.error("idp_authorize_error clientId={} username={}", parData.clientId(), username, e);
            return new RedirectView("/idp/error?code=server_error");
        }
    }

    /**
     * A hosted form bound to a PAR ends in a 302 to that PAR's redirect_uri; CSP form-action
     * is enforced on that hop too, so the page must allow exactly that origin (see
     * {@link IdpHardeningFilter#cspAllowingFormRedirectTo}). No PAR → strict policy stays.
     */
    private static void allowFormRedirectTo(jakarta.servlet.http.HttpServletResponse response,
                                            ParService.ParRequestData parData) {
        if (parData != null) {
            response.setHeader(IdpHardeningFilter.CSP_HEADER,
                IdpHardeningFilter.cspAllowingFormRedirectTo(parData.redirectUri()));
        }
    }

    private static String html(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ========== F-8: forced first-login password change (hosted at the IdP) ==========

    private static final int PW_MIN_LENGTH = 10;
    private static final int PW_MAX_LENGTH = 128;

    /** Hosted change form: credentials and the replacement never leave the IdP origin. */
    private String changePage(String ticket, String error) {
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>FSP Identity Provider &middot; Set your password</title>
            <style>
              body { font-family: system-ui, sans-serif; background: #101418; color: #e8edf2;
                     display: grid; place-items: center; min-height: 100dvh; margin: 0; }
              .panel { background: #1a2029; border: 1px solid #2a323d; border-radius: 12px;
                       padding: 2rem; width: min(92vw, 22rem); }
              h1 { font-size: 1.05rem; margin: 0 0 .25rem; }
              p.eyebrow { font-size: .7rem; letter-spacing: .12em; text-transform: uppercase;
                          color: #f0a10e; margin: 0 0 .75rem; }
              label { display: block; font-size: .75rem; margin: .75rem 0 .25rem; color: #9fb0c0; }
              input { width: 100%%; box-sizing: border-box; padding: .55rem .7rem; border-radius: 8px;
                      border: 1px solid #3a4450; background: #101418; color: #e8edf2; font: inherit; }
              button { margin-top: 1.25rem; width: 100%%; padding: .6rem; border: 0; border-radius: 8px;
                       background: #f0a10e; color: #101418; font-weight: 700; cursor: pointer; }
              .hint { margin-top: 1rem; font-size: .7rem; color: #7a8a99; line-height: 1.5; }
              .error { margin-top: 1rem; font-size: .75rem; color: #e5484d; }
              .error:empty { display: none; }
              .pw-row { display: flex; gap: .4rem; align-items: stretch; }
              .pw-row input { width: auto; flex: 1 1 auto; min-width: 0; }
              .pw-toggle { margin-top: 0; width: auto; flex: 0 0 auto; padding: .55rem .7rem;
                           border: 1px solid #3a4450; border-radius: 8px; background: transparent;
                           color: #9fb0c0; font: inherit; font-size: .7rem; cursor: pointer; }
              .pw-toggle:hover { border-color: #f0a10e; color: #f0a10e; }
            </style></head><body><div class="panel">
            <p class="eyebrow">FSP Identity Provider</p>
            <h1>Set your own password</h1>
            <form method="post" action="/idp/password/complete">
              <input type="hidden" name="ticket" value="%s">
              <label for="np">New password</label>
              <div class="pw-row">
                <input id="np" name="new_password" type="password" autocomplete="new-password" required autofocus>
                <button type="button" class="pw-toggle" data-pw-toggle="np"
                        aria-pressed="false" aria-label="Show password">Show</button>
              </div>
              <label for="npc">Confirm new password</label>
              <div class="pw-row">
                <input id="npc" name="confirm_password" type="password" autocomplete="new-password" required>
                <button type="button" class="pw-toggle" data-pw-toggle="npc"
                        aria-pressed="false" aria-label="Show password">Show</button>
              </div>
              <button type="submit">Set new password</button>
              <p class="error" id="chg-err">%s</p>
            </form>
            <p class="hint">At least %d characters. Your credentials never leave the Identity Provider.</p>
            </div></body></html>
            """.formatted(html(ticket), html(error), PW_MIN_LENGTH).replace("</head>", SUBMIT_ONCE_TAG + "</head>");
    }

    // POST only: the replacement secret travels in the form body, never the URL
    @PostMapping(value = "/password/complete", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Object passwordChangeComplete(@RequestParam("ticket") String ticket,
                                         @RequestParam("new_password") String newPassword,
                                         @RequestParam("confirm_password") String confirmPassword,
                                         jakarta.servlet.http.HttpServletResponse response) {
        ParService.PasswordChangeTicket t = parService.peekPasswordChangeTicket(ticket);
        if (t == null) {
            // Unknown/expired (already consumed, or the user sat on the form > 10 min)
            log.warn("idp_password_change_failed reason=ticket_invalid");
            return new RedirectView("/idp/error?code=request_uri_invalid");
        }
        allowFormRedirectTo(response, t.parData());
        if (newPassword == null || newPassword.length() < PW_MIN_LENGTH || newPassword.length() > PW_MAX_LENGTH) {
            log.warn("idp_password_change_rejected username={} reason=policy_length", t.username());
            return changePage(ticket, "Password must be %d–%d characters.".formatted(PW_MIN_LENGTH, PW_MAX_LENGTH));
        }
        if (!newPassword.equals(confirmPassword)) {
            log.warn("idp_password_change_rejected username={} reason=confirmation_mismatch", t.username());
            return changePage(ticket, "The two passwords do not match.");
        }
        PPerson person = persons.findById(t.personId()).orElse(null);
        if (person == null) {
            log.warn("idp_password_change_failed reason=person_missing username={}", t.username());
            return new RedirectView("/idp/error?code=account_inactive");
        }
        if (bcrypt.matches(newPassword, person.passwordHash)) {
            log.warn("idp_password_change_rejected username={} reason=same_as_current", t.username());
            return changePage(ticket, "Choose a password you have not used here before.");
        }
        person.passwordHash = bcrypt.encode(newPassword);
        person.mustChangePassword = false;
        persons.save(person);
        log.info("idp_password_changed personId={} username={}", person.id, t.username());

        // Resume the parked sign-in: same PAR data, same client, same state
        String authCode = parService.storeAuthCode(t.parData(), person.id, person.tenantId);
        StringBuilder redirectUri = new StringBuilder(t.parData().redirectUri())
            .append("?code=").append(URLEncoder.encode(authCode, StandardCharsets.UTF_8));
        if (t.parData().state() != null) {
            redirectUri.append("&state=").append(URLEncoder.encode(t.parData().state(), StandardCharsets.UTF_8));
        }
        redirectUri.append("&iss=").append(URLEncoder.encode(props.getIssuer(), StandardCharsets.UTF_8));
        parService.consumePasswordChangeTicket(ticket);
        log.info("idp_authorize_resumed clientId={} username={}", t.parData().clientId(), t.username());
        return new RedirectView(redirectUri.toString());
    }

    // ========== V30: MFA second step, hosted at the IdP (after the password step) ==========

    private Object mfaPage(String ticket, String error) {
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <title>FSP — verification code</title><style>
            body { font-family: system-ui, sans-serif; background: #0d1117; color: #e8edf2;
                   display: grid; place-items: center; min-height: 100vh; margin: 0; }
            .panel { background: #161b22; border: 1px solid #2a323d; border-radius: 12px;
                     padding: 2rem; width: min(92vw, 22rem); }
            h1 { font-size: 1.05rem; margin: 0 0 .25rem; }
            p.eyebrow { font-size: .7rem; letter-spacing: .12em; text-transform: uppercase;
                        color: #f0a10e; margin: 0 0 .75rem; }
            label { display: block; font-size: .75rem; margin: .75rem 0 .25rem; color: #9fb0c0; }
            input { width: 100%%; box-sizing: border-box; padding: .55rem .7rem; border-radius: 8px;
                    border: 1px solid #3a4450; background: #101418; color: #e8edf2;
                    font: inherit; letter-spacing: .3em; }
            button { margin-top: 1.25rem; width: 100%%; padding: .6rem; border: 0; border-radius: 8px;
                     background: #f0a10e; color: #101418; font-weight: 700; cursor: pointer; }
            .hint { margin-top: 1rem; font-size: .7rem; color: #7a8a99; line-height: 1.5; }
            .error { margin-top: 1rem; font-size: .75rem; color: #e5484d; }
            .error:empty { display: none; }
            </style></head><body><div class="panel">
            <p class="eyebrow">FSP Identity Provider</p>
            <h1>Enter your verification code</h1>
            <form method="post" action="/idp/mfa/verify">
              <input type="hidden" name="ticket" value="%s">
              <label for="otp">Verification code</label>
              <input id="otp" name="otp" inputmode="numeric" pattern="[0-9]*" required autofocus>
              <button type="submit">Sign in</button>
              <p class="error" id="mfa-err">%s</p>
            </form>
            <p class="hint">A code was sent to your registered contact. It works for 5 minutes.
            FSP never gives codes over the phone.</p>
            </div></body></html>
            """.formatted(html(ticket), html(error)).replace("</head>", SUBMIT_ONCE_TAG + "</head>");
    }

    // POST only: OTP travels in the form body, never the URL
    @PostMapping(value = "/mfa/verify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Object mfaVerify(@RequestParam("ticket") String ticket,
                            @RequestParam("otp") String otp,
                            jakarta.servlet.http.HttpServletResponse response) {
        ParService.MfaTicket t = parService.peekMfaTicket(ticket);
        if (t == null) {
            log.warn("idp_mfa_verify_failed reason=ticket_invalid");
            return new RedirectView("/idp/error?code=request_uri_invalid");
        }
        allowFormRedirectTo(response, t.parData());
        try {
            auth.verifyLoginCode(t.personId(), otp == null ? "" : otp.trim());
        } catch (IdpProblemException e) {
            log.warn("idp_mfa_verify_rejected username={} problem={}", t.username(), e.problem().code());
            return mfaPage(ticket, "Code rejected. " + e.problem().detail());
        }
        String authCode = parService.storeAuthCode(t.parData(), t.personId(), t.tenantId());
        StringBuilder redirectUri = new StringBuilder(t.parData().redirectUri())
            .append("?code=").append(URLEncoder.encode(authCode, StandardCharsets.UTF_8));
        if (t.parData().state() != null) {
            redirectUri.append("&state=").append(URLEncoder.encode(t.parData().state(), StandardCharsets.UTF_8));
        }
        redirectUri.append("&iss=").append(URLEncoder.encode(props.getIssuer(), StandardCharsets.UTF_8));
        parService.consumeMfaTicket(ticket);
        log.info("idp_mfa_verified username={} clientId={}", t.username(), t.parData().clientId());
        return new RedirectView(redirectUri.toString());
    }

    // ========== V30: self-service reset + MFA enrolment (bearer-authenticated) ==========

    /** Pre-auth reset request — ALWAYS 204, never reveals whether the username exists (RFC 9700). */
    @PostMapping(value = "/password/reset-request", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> passwordResetRequest(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username != null && !username.isBlank()) {
            try {
                auth.requestPasswordReset(username.trim());
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // still 204 (never reveals the account) — but only a unique-key race is benign
                if (isUniqueViolation(e)) log.info("idp_reset_duplicate_suppressed");
                else log.warn("idp_reset_failed sqlState={}", sqlState(e));
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/password/reset-confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> passwordResetConfirm(@RequestBody Map<String, String> body) {
        auth.confirmPasswordReset(body.get("token"), body.get("newPassword"), body.get("confirmPassword"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/mfa", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object mfaStatus(@RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID personId = tokenService.personFromBearer(authorization);
        return auth.state(personId);
    }

    /** Begin enrolment: a confirmation code goes to the chosen channel (possession proof). */
    @PutMapping(value = "/mfa", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> mfaBegin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, String> body) {
        UUID personId = tokenService.personFromBearer(authorization);
        auth.beginEnrolment(personId, body.get("channel"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/mfa/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> mfaConfirm(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Map<String, String> body) {
        UUID personId = tokenService.personFromBearer(authorization);
        auth.confirmEnrolment(personId, body.get("code"));
        return ResponseEntity.noContent().build();
    }

    /** Disable requires the current password (knowledge proof). */
    @DeleteMapping(value = "/mfa", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> mfaDisable(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Map<String, String> body) {
        UUID personId = tokenService.personFromBearer(authorization);
        auth.disable(personId, body.get("password"));
        return ResponseEntity.noContent().build();
    }

    // ========== Token Endpoint (ROPC - legacy + auth_code - FAPI 2.0) ==========

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public IdpDtos.TokenResponse token(@RequestBody Map<String, String> body,
                                        @RequestHeader(value = "DPoP", required = false) String dpopHeader) {
        String grantType = body.get("grant_type");
        
        if ("client_credentials".equals(grantType)) {
            // FAPI 2.0 machine-plane: client_credentials + private_key_jwt + DPoP
            IdpDtos.ClientCredentialsGrant grant = new IdpDtos.ClientCredentialsGrant(
                body.get("grant_type"),
                body.get("client_id"),
                body.get("client_assertion"),
                body.get("client_assertion_type"),
                body.get("scope")
            );
            return tokenService.grantClientCredentials(grant, dpopHeader);
        } else if ("password".equals(grantType)) {
            // Legacy ROPC (will be deprecated)
            IdpDtos.TokenGrant grant = new IdpDtos.TokenGrant(
                body.get("grant_type"),
                body.get("username"),
                body.get("password"),
                body.get("client_id"),
                body.get("otp")
            );
            return tokenService.grantPassword(grant);
        } else if ("authorization_code".equals(grantType)) {
            // FAPI 2.0: Authorization code flow with PKCE + DPoP + private_key_jwt
            IdpDtos.TokenExchangeRequest request = new IdpDtos.TokenExchangeRequest(
                body.get("grant_type"),
                body.get("code"),
                body.get("code_verifier"),
                body.get("redirect_uri"),
                body.get("client_assertion"),
                body.get("client_assertion_type")
            );
            
            // Consume auth code (validates PKCE)
            ParService.AuthCodeData authCodeData = parService.consumeAuthCode(request.code(), request.codeVerifier());
            
            // Exchange for token (validates client_assertion, DPoP)
            return tokenService.exchangeAuthCode(request, dpopHeader, authCodeData);
        } else {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "UNSUPPORTED_GRANT", "grant_type must be 'password' or 'authorization_code'", 400));
        }
    }

    // ========== Existing endpoints ==========

    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public String jwks() {
        return tokenService.jwksJson();
    }

    @PostMapping(value = "/introspect", consumes = MediaType.TEXT_PLAIN_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public IdpDtos.IntrospectionResponse introspect(@RequestBody String token) {
        return tokenService.introspect(token == null ? "" : token.trim());
    }

    @PostMapping(value = "/introspect", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public IdpDtos.IntrospectionResponse introspectJson(@RequestBody Map<String, String> body) {
        return tokenService.introspect(body.getOrDefault("token", ""));
    }

    @GetMapping(value = "/policy/{tenantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public IdpDtos.PolicyView policy(@PathVariable("tenantId") UUID tenantId) {
        return tokenService.policy(tenantId);
    }

    @PutMapping(value = "/policy/{tenantId}", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public IdpDtos.PolicyView updatePolicy(@PathVariable("tenantId") UUID tenantId,
                                           @RequestBody IdpDtos.PolicyUpdate update,
                                           @RequestHeader(value = "X-FSP-User-Id", required = false) String updatedBy) {
        UUID actor = null;
        if (updatedBy != null && !updatedBy.isBlank()) {
            try {
                actor = UUID.fromString(updatedBy);
            } catch (IllegalArgumentException ignored) {
                actor = null;
            }
        }
        return tokenService.updatePolicy(tenantId, update, actor);
    }

    @GetMapping(value = "/.well-known/openid-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> discovery() {
        return Map.of(
            "issuer", props.getIssuer(),
            "token_endpoint", "/idp/token",
            "jwks_uri", "/idp/jwks",
            "pushed_authorization_request_endpoint", "/idp/par",
            "authorization_endpoint", "/idp/authorize",
            "grant_types_supported", "authorization_code, password, client_credentials");
    }

    // ========== V36: account activation (hosted at the IdP; reached via the gateway) ==========

    /** Internal hop from core (credential-gated): (re)send the person's activation. Always 204. */
    @PostMapping(value = "/internal/activation/{personId}")
    public ResponseEntity<Void> internalActivation(@PathVariable("personId") UUID personId,
            @RequestHeader(value = "X-FSP-Internal-Credential", required = false) String credential) {
        if (credential == null || !credential.equals(props.getInternalCredential())) {
            log.warn("idp_internal_activation_refused reason=credential_invalid");
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INTERNAL_CREDENTIAL_INVALID", "Internal credential required", 401));
        }
        try {
            activation.send(personId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // a concurrent send won the one-live-row race (its link/code is on the way); any
            // other constraint failure is a real fault and must be visible, never "a duplicate"
            if (isUniqueViolation(e)) log.info("idp_activation_duplicate_suppressed person={}", personId);
            else log.warn("idp_activation_failed person={} sqlState={}", personId, sqlState(e));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The activation page. With {@code ?token=} (the emailed link) it asks only for the new
     * password; without it (SMS) it asks for the phone, the code and the new password.
     * The token is not consumed by viewing; the page is no-referrer + strict CSP (hardening filter).
     */
    @GetMapping(value = "/activate", produces = MediaType.TEXT_HTML_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public String activatePage(@RequestParam(value = "token", required = false) String token) {
        if (token != null && !token.isBlank()) {
            if (!activation.linkIsLive(token)) {
                return activationMessage("This link has expired or was already used",
                    "Ask the person who opened your account to send you a new activation.");
            }
            return activationForm(token, "");
        }
        return activationForm(null, "");
    }

    // POST only: the password and the code travel in the form body, never the URL
    @PostMapping(value = "/activate", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.TEXT_HTML_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public String activateSubmit(@RequestParam(value = "token", required = false) String token,
                                 @RequestParam(value = "phone", required = false) String phone,
                                 @RequestParam(value = "code", required = false) String code,
                                 @RequestParam("new_password") String newPassword,
                                 @RequestParam("confirm_password") String confirmPassword) {
        boolean byLink = token != null && !token.isBlank();
        IdpActivationService.Proof proof = byLink
            ? new IdpActivationService.Proof.Link(token)
            : new IdpActivationService.Proof.Code(phone, code);
        try {
            var done = activation.complete(proof, newPassword, confirmPassword);
            return done.signInNow()
                ? activationMessage("Your account is active",
                    "Sign in from your portal as " + html(done.username()) + " with the password you just chose.")
                : activationMessage("Your password is set",
                    "Your account is being reviewed. You can sign in as " + html(done.username())
                        + " once it has been approved — we will let you know.");
        } catch (IdpProblemException e) {
            log.warn("idp_activation_rejected channel={} problem={}", byLink ? "EMAIL" : "SMS", e.problem().code());
            if (byLink && "ACTIVATION_INVALID".equals(e.problem().code())) {
                return activationMessage("This link has expired or was already used",
                    "Ask the person who opened your account to send you a new activation.");
            }
            return activationForm(byLink ? token : null, e.problem().detail());
        }
    }

    private static final String ACTIVATION_STYLE = """
        <style>
          body { font-family: system-ui, sans-serif; background: #101418; color: #e8edf2;
                 display: grid; place-items: center; min-height: 100dvh; margin: 0; }
          .panel { background: #1a2029; border: 1px solid #2a323d; border-radius: 12px;
                   padding: 2rem; width: min(92vw, 24rem); box-sizing: border-box; }
          h1 { font-size: 1.05rem; margin: 0 0 .25rem; }
          p.eyebrow { font-size: .7rem; letter-spacing: .12em; text-transform: uppercase;
                      color: #f0a10e; margin: 0 0 .75rem; }
          label { display: block; font-size: .75rem; margin: .75rem 0 .25rem; color: #9fb0c0; }
          input { width: 100%; box-sizing: border-box; padding: .55rem .7rem; border-radius: 8px;
                  border: 1px solid #3a4450; background: #101418; color: #e8edf2; font: inherit; }
          button { margin-top: 1.25rem; width: 100%; padding: .6rem; border: 0; border-radius: 8px;
                   background: #f0a10e; color: #101418; font-weight: 700; cursor: pointer; }
          .hint { margin-top: 1rem; font-size: .7rem; color: #7a8a99; line-height: 1.5; }
          .error { margin-top: 1rem; font-size: .75rem; color: #e5484d; }
          .error:empty { display: none; }
          p.body { color: #9fb0c0; line-height: 1.5; }
        </style>""";

    private String activationForm(String token, String error) {
        String proofFields = token != null
            ? "<input type=\"hidden\" name=\"token\" value=\"" + html(token) + "\">"
            : """
              <label for="ph">Mobile number</label>
              <input id="ph" name="phone" type="tel" autocomplete="tel" placeholder="+264 81 123 4567" required autofocus>
              <label for="code">Activation code from the SMS</label>
              <input id="code" name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9 ]*" required>
              """;
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>FSP Identity Provider &middot; Activate your account</title>" + ACTIVATION_STYLE + SUBMIT_ONCE_TAG
            + "</head><body><div class=\"panel\"><p class=\"eyebrow\">FSP Identity Provider</p>"
            + "<h1>Activate your account</h1>"
            + "<form method=\"post\" action=\"/idp/activate\">" + proofFields
            + "<label for=\"np\">Choose a password</label>"
            + "<input id=\"np\" name=\"new_password\" type=\"password\" autocomplete=\"new-password\" required"
            + (token != null ? " autofocus" : "") + ">"
            + "<label for=\"npc\">Confirm password</label>"
            + "<input id=\"npc\" name=\"confirm_password\" type=\"password\" autocomplete=\"new-password\" required>"
            + "<button type=\"submit\">Activate</button>"
            + "<p class=\"error\">" + html(error) + "</p></form>"
            + "<p class=\"hint\">At least " + PW_MIN_LENGTH + " characters. Only you know this password &mdash; "
            + "FSP staff never see it and will never ask for it.</p></div></body></html>";
    }

    private static String activationMessage(String title, String bodyHtml) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>FSP Identity Provider &middot; Activation</title>" + ACTIVATION_STYLE
            + "</head><body><div class=\"panel\"><p class=\"eyebrow\">FSP Identity Provider</p>"
            + "<h1>" + html(title) + "</h1><p class=\"body\">" + bodyHtml + "</p></div></body></html>";
    }

    // ========== Internal: OAuth client management (core only) ==========

    /** Admin-initiated password reset (internal hop from core, credential-gated).
     *  Always 204 — never reveals whether the person exists (RFC 9700 discipline).
     *  Core has already validated person + org membership; the IdP only dispatches
     *  the reset link to the person's email. Core never sees or touches the new password. */
    @PostMapping(value = "/internal/password-reset/{personId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> internalPasswordReset(@PathVariable("personId") UUID personId,
            @RequestHeader(value = "X-FSP-Internal-Credential", required = false) String credential) {
        if (credential == null || !credential.equals(props.getInternalCredential())) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INTERNAL_CREDENTIAL_INVALID", "Internal credential required", 401));
        }
        // Internal hop: core already validated person exists & belongs to the org.
        PPerson person = persons.findById(personId).orElse(null);
        if (person != null) {
            // Silent no-op if inactive / no usable email — RFC 9700 enumeration resistance.
            auth.requestPasswordReset(person.username);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/internal/clients", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public IdpDtos.ClientView createClient(@RequestBody IdpDtos.ClientCreate body,
                                           @RequestHeader(value = "X-FSP-Internal-Credential", required = false) String credential) {
        if (credential == null || !credential.equals(props.getInternalCredential())) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INTERNAL_CREDENTIAL_INVALID", "Internal credential required", 401));
        }
        if (body.clientId() == null || body.clientId().isBlank()
            || body.ownerTenantId() == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "client_id and ownerTenantId are required", 400));
        }
        // Check uniqueness via JDBC
        try {
            jdbc.queryForObject("SELECT 1 FROM oauth_clients WHERE client_id = ?",
                new Object[]{body.clientId()}, Integer.class);
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "CLIENT_EXISTS", "Client " + body.clientId() + " already registered", 409));
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
            // not found — good, proceed
        }
        jdbc.update("""
            INSERT INTO oauth_clients (client_id, client_type, jwks, grant_types, status, owner_tenant_id, token_ttl_s, created_at, updated_at)
            VALUES (?, 'MACHINE', ?, '["client_credentials"]', 'ACTIVE', ?, ?, now(), now())
            """, body.clientId(), body.jwks(), body.ownerTenantId(), body.tokenTtlSeconds() == null ? 300 : body.tokenTtlSeconds());
        log.info("idp_client_created clientId={} ownerTenant={}", body.clientId(), body.ownerTenantId());
        return new IdpDtos.ClientView(body.clientId(), "MACHINE", body.jwks(),
            List.of("client_credentials"), "ACTIVE", body.ownerTenantId(),
            body.tokenTtlSeconds() == null ? 300 : body.tokenTtlSeconds());
    }

    @PatchMapping(value = "/internal/clients/{clientId}", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public IdpDtos.ClientView patchClient(@PathVariable("clientId") String clientId,
                                          @RequestBody IdpDtos.ClientUpdate body,
                                          @RequestHeader(value = "X-FSP-Internal-Credential", required = false) String credential) {
        if (credential == null || !credential.equals(props.getInternalCredential())) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INTERNAL_CREDENTIAL_INVALID", "Internal credential required", 401));
        }
        var row = jdbc.queryForMap("SELECT client_id, status FROM oauth_clients WHERE client_id = ?", clientId);
        String currentStatus = (String) row.get("status");
        String newStatus = body.status() == null ? currentStatus : body.status();
        if (!Set.of("ACTIVE", "SUSPENDED", "DEACTIVATED").contains(newStatus)) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_STATUS", "status must be ACTIVE, SUSPENDED or DEACTIVATED", 400));
        }
        jdbc.update("UPDATE oauth_clients SET status = ?, updated_at = now() WHERE client_id = ?",
            newStatus, clientId);
        log.info("idp_client_patched clientId={} status={} -> {}", clientId, currentStatus, newStatus);
        var updated = jdbc.queryForMap("SELECT client_id, client_type, jwks, grant_types, status, owner_tenant_id, token_ttl_s FROM oauth_clients WHERE client_id = ?", clientId);
        return new IdpDtos.ClientView(
            (String) updated.get("client_id"), (String) updated.get("client_type"),
            (String) updated.get("jwks"),
            List.of("client_credentials"), (String) updated.get("status"),
            UUID.fromString((String) updated.get("owner_tenant_id")),
            ((Number) updated.get("token_ttl_s")).intValue());
    }

    /** Postgres unique_violation (23505) anywhere in the cause chain. */
    static boolean isUniqueViolation(Throwable e) {
        return "23505".equals(sqlState(e));
    }

    /** The SQLSTATE of the first SQLException in the chain (no data, safe to log), or null. */
    static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }
}
