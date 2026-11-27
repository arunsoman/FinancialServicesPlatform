package com.fsp.idp;

import com.fsp.shared.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PAR (Pushed Authorization Request) service per RFC 9126.
 * Stores authorization request parameters server-side and returns opaque request_uri.
 * request_uri is single-use and expires (typically 30-60 seconds).
 */
@Service
public class ParService {

    private static final Logger log = LoggerFactory.getLogger(ParService.class);
    private static final int PAR_EXPIRY_SECONDS = 60;
    private static final java.util.regex.Pattern STATE_PATTERN =
        java.util.regex.Pattern.compile("^[A-Za-z0-9._~-]{1,256}$");

    private final JwtService jwt;
    private final IdpProperties props;

    // In-memory store for PAR requests (production: Redis with TTL)
    // Key: request_uri (opaque), Value: ParRequestData
    private final Map<String, ParRequestData> parStore = new ConcurrentHashMap<>();

    // Store for issued authorization codes (single-use, short-lived)
    // Key: auth_code, Value: AuthCodeData
    private final Map<String, AuthCodeData> authCodeStore = new ConcurrentHashMap<>();

    // F-8: forced first-login password change. The ticket parks the PAR data (already
    // consumed from parStore at the callback) so the change form can resume the sign-in
    // flow after the new password is set — no second authentication round.
    // Ticket is re-readable until success or expiry (validation failures re-render the form).
    private static final long PW_TICKET_EXPIRY_SECONDS = 600;
    private final Map<String, ParService.PasswordChangeTicket> pwTicketStore = new ConcurrentHashMap<>();

    public record PasswordChangeTicket(String username, UUID personId, UUID tenantId,
                                       ParRequestData parData, Instant createdAt) {}

    public String storePasswordChangeTicket(String username, UUID personId, UUID tenantId,
                                            ParRequestData parData) {
        String ticket = "pwt_" + UUID.randomUUID().toString().replace("-", "");
        pwTicketStore.put(ticket, new PasswordChangeTicket(username, personId, tenantId, parData, Instant.now()));
        log.debug("password_change_ticket_stored username={} clientId={}", username, parData.clientId());
        return ticket;
    }

    /** Peek (non-consuming): validation failures re-render the form with the same ticket. */
    public PasswordChangeTicket peekPasswordChangeTicket(String ticket) {
        PasswordChangeTicket t = pwTicketStore.get(ticket);
        if (t == null) {
            return null;
        }
        if (Instant.now().isAfter(t.createdAt().plusSeconds(PW_TICKET_EXPIRY_SECONDS))) {
            pwTicketStore.remove(ticket);
            log.warn("password_change_ticket_expired");
            return null;
        }
        return t;
    }

    /** Consume strictly once — only on a successful password change. */
    public PasswordChangeTicket consumePasswordChangeTicket(String ticket) {
        PasswordChangeTicket t = peekPasswordChangeTicket(ticket);
        if (t != null) {
            pwTicketStore.remove(ticket);
        }
        return t;
    }

    // ---------- V30: MFA second-step ticket (hosted authorize flow; OTP-sized TTL) ----------

    private static final long MFA_TICKET_EXPIRY_SECONDS = 300;
    private final Map<String, MfaTicket> mfaTicketStore = new ConcurrentHashMap<>();

    /** Identity is already proven (password verified, challenge dispatched) — only the OTP is pending. */
    public record MfaTicket(String username, UUID personId, UUID tenantId,
                            ParRequestData parData, Instant createdAt) {}

    public String storeMfaTicket(String username, UUID personId, UUID tenantId, ParRequestData parData) {
        String ticket = "mfa_" + UUID.randomUUID().toString().replace("-", "");
        mfaTicketStore.put(ticket, new MfaTicket(username, personId, tenantId, parData, Instant.now()));
        log.debug("mfa_ticket_stored username={} clientId={}", username, parData.clientId());
        return ticket;
    }

    /** Peek (non-consuming): a wrong/expired code re-renders the code form with the same ticket. */
    public MfaTicket peekMfaTicket(String ticket) {
        MfaTicket t = mfaTicketStore.get(ticket);
        if (t == null) {
            return null;
        }
        if (Instant.now().isAfter(t.createdAt().plusSeconds(MFA_TICKET_EXPIRY_SECONDS))) {
            mfaTicketStore.remove(ticket);
            log.warn("mfa_ticket_expired");
            return null;
        }
        return t;
    }

    /** Consume strictly once — only after the second factor verified. */
    public MfaTicket consumeMfaTicket(String ticket) {
        MfaTicket t = peekMfaTicket(ticket);
        if (t != null) {
            mfaTicketStore.remove(ticket);
        }
        return t;
    }

    public ParService(JwtService jwt, IdpProperties props) {
        this.jwt = jwt;
        this.props = props;
    }

    public record ParRequestData(
        String clientId,
        String codeChallenge,
        String codeChallengeMethod,
        String redirectUri,
        String scope,
        String authorizationDetails,
        String resource,
        String state,
        Instant createdAt
    ) {}

    public record AuthCodeData(
        String clientId,
        String redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        String scope,
        UUID personId,
        UUID tenantId,
        Instant createdAt
    ) {}

    /**
     * Process PAR request: validate client auth, PKCE, store params, return request_uri.
     */
    public IdpDtos.ParResponse createParRequest(IdpDtos.ParRequest request, String clientAssertion) {
        // 1. Validate client authentication (private_key_jwt)
        String clientId = validateClientAssertion(clientAssertion);
        if (clientId == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_CLIENT", "Invalid client_assertion", 401));
        }

        // 2. Validate PKCE
        if (request.codeChallenge() == null || request.codeChallenge().isBlank()) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "code_challenge is required", 400));
        }
        if (!"S256".equals(request.codeChallengeMethod())) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "code_challenge_method must be S256", 400));
        }

        // 3. Validate redirect_uri
        if (request.redirectUri() == null || request.redirectUri().isBlank()) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "redirect_uri is required", 400));
        }

        // 4. Validate response_type
        if (!"code".equals(request.responseType())) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "response_type must be 'code'", 400));
        }

        // 5. Validate state (optional, opaque to us; echoed verbatim on the redirect)
        if (request.state() != null && !STATE_PATTERN.matcher(request.state()).matches()) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "state must be 1-256 chars of A-Za-z0-9._~-", 400));
        }

        // 6. Generate opaque request_uri
        String requestUri = "urn:ietf:params:oauth:request_uri:" + UUID.randomUUID().toString().replace("-", "");

        // 7. Store PAR data
        ParRequestData data = new ParRequestData(
            clientId,
            request.codeChallenge(),
            request.codeChallengeMethod(),
            request.redirectUri(),
            request.scope(),
            request.authorizationDetails(),
            request.resource(),
            request.state(),
            Instant.now()
        );
        parStore.put(requestUri, data);

        // 8. Schedule cleanup (simple approach: clean on access)
        log.debug("par_created requestUri={} clientId={}", requestUri, clientId);

        return new IdpDtos.ParResponse(requestUri, PAR_EXPIRY_SECONDS);
    }

    /**
     * Peek (non-consuming) PAR data for the authorization endpoint — the request_uri
     * remains valid until the callback truly consumes it (it is single-use in total).
     * RFC 9126 §4 / FAPI 2.0: once the authorization request has been fetched from the
     * pushed endpoint, the transaction lives AT the authorization endpoint; the 60 s
     * PAR TTL no longer runs against the user's sign-in interaction, so the peek
     * re-anchors the TTL (the entry is consumed exactly once at the callback).
     */
    public ParRequestData peekParRequest(String requestUri) {
        ParRequestData data = parStore.get(requestUri);
        if (data == null) {
            return null;
        }
        if (Instant.now().isAfter(data.createdAt().plusSeconds(PAR_EXPIRY_SECONDS))) {
            parStore.remove(requestUri);
            log.warn("par_expired requestUri={}", requestUri);
            return null;
        }
        parStore.put(requestUri, new ParRequestData(data.clientId(), data.codeChallenge(), data.codeChallengeMethod(),
            data.redirectUri(), data.scope(), data.authorizationDetails(), data.resource(), data.state(), Instant.now()));
        return data;
    }

    /**
     * Retrieve and consume PAR data for authorization completion.
     * Returns null if not found, expired, or already consumed.
     */
    public ParRequestData consumeParRequest(String requestUri) {
        ParRequestData data = parStore.remove(requestUri);
        if (data == null) {
            return null;
        }
        // Check expiry
        if (Instant.now().isAfter(data.createdAt().plusSeconds(PAR_EXPIRY_SECONDS))) {
            log.warn("par_expired requestUri={}", requestUri);
            return null;
        }
        return data;
    }

    /**
     * Store authorization code after user consent.
     */
    public String storeAuthCode(ParRequestData parData, UUID personId, UUID tenantId) {
        String authCode = "auth_" + UUID.randomUUID().toString().replace("-", "");
        AuthCodeData data = new AuthCodeData(
            parData.clientId(),
            parData.redirectUri(),
            parData.codeChallenge(),
            parData.codeChallengeMethod(),
            parData.scope(),
            personId,
            tenantId,
            Instant.now()
        );
        authCodeStore.put(authCode, data);
        log.debug("auth_code_stored authCode={} clientId={}", authCode, parData.clientId());
        return authCode;
    }

    /**
     * Consume authorization code for token exchange.
     * Validates PKCE verifier, single-use, and expiry (60 seconds).
     */
    public AuthCodeData consumeAuthCode(String authCode, String codeVerifier) {
        AuthCodeData data = authCodeStore.remove(authCode);
        if (data == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_GRANT", "Authorization code not found or already used", 400));
        }

        // Check expiry (FAPI 2.0: max 60 seconds)
        if (Instant.now().isAfter(data.createdAt().plusSeconds(60))) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_GRANT", "Authorization code expired", 400));
        }

        // Validate PKCE verifier
        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "code_verifier is required", 400));
        }
        String expectedChallenge = computePkceChallenge(codeVerifier, data.codeChallengeMethod());
        if (!expectedChallenge.equals(data.codeChallenge())) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_GRANT", "code_verifier does not match code_challenge", 400));
        }

        return data;
    }

    private String validateClientAssertion(String clientAssertion) {
        // ES256 assertion with embedded public JWK, bound to the issuer (FAPI 2.0)
        String clientId = ClientAssertions.validate(clientAssertion, props.getIssuer());
        if (clientId == null) {
            log.warn("client_assertion_validation_failed");
        }
        return clientId;
    }

    private String computePkceChallenge(String verifier, String method) {
        if (!"S256".equals(method)) {
            throw new IllegalArgumentException("Unsupported PKCE method: " + method);
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}