package com.fsp.bff;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BFF client for IdP communication (FAPI 2.0 compliant).
 * Implements: PAR → Authorization Code Flow with PKCE + DPoP + private_key_jwt.
 * The BFF is a confidential client - never exposes credentials to the browser.
 *
 * Crypto model: one long-lived EC P-256 client key (private_key_jwt, ES256, public
 * JWK embedded in the assertion header) plus a per-session EC P-256 DPoP key
 * (ES256 proofs; the session JWK round-trips through the HTTP session).
 * JWS ES256 = raw r||s, so all signing uses SHA256withECDSA-in-P1363.
 */
@Component
public class IdpClient {

    private static final Logger log = LoggerFactory.getLogger(IdpClient.class);
    private static final String CLIENT_ID = "fsp-bff";

    /** Response of the IdP token grant (mirrors IdpDtos, BFF-local record per §2b). */
    public record TokenClaims(String userId, String username, String fullName, String tenantId,
                              String tenantName, String tenantType, java.util.List<String> roles) {}

    /** refreshToken: present only on the authorization_code grant (FAPI 2.0: never rotated). */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                TokenClaims claims, String refreshToken) {}

    private final RestClient rest;
    private final BffProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    /** Long-lived client key pair for private_key_jwt (in production: Vault/HSM). */
    private final KeyPair clientKeyPair;

    public IdpClient(BffProperties props) {
        this.props = props;
        this.rest = RestClient.builder().baseUrl(props.getGateway().getBaseUrl()).build();
        this.clientKeyPair = generateP256();
    }

    private static KeyPair generateP256() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate P-256 key pair", e);
        }
    }

    /** Sign a JWS with ES256: JCA DER output converted to JWS raw r||s. */
    private String signEs256(PrivateKey key, String signingInput) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(key);
        sig.update(signingInput.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] raw = com.fsp.shared.JwtService.ecdsaDerToJws(sig.sign(), 32);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String b64u(BigInteger v, int bytes) {
        byte[] raw = new byte[bytes];
        byte[] b = v.toByteArray();
        int off = (b.length > bytes) ? b.length - bytes : 0;
        int len = Math.min(b.length, bytes);
        System.arraycopy(b, off, raw, bytes - len, len);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    /** Public-only JWK (kty/crv/x/y) — safe for headers and cnf claims. */
    public static Map<String, Object> publicJwk(ECPublicKey key) {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("x", b64u(key.getW().getAffineX(), 32));
        jwk.put("y", b64u(key.getW().getAffineY(), 32));
        return jwk;
    }

    /** Private-session JWK (adds d) — only ever stored in the server-side HTTP session. */
    public static Map<String, Object> privateJwk(KeyPair pair) {
        Map<String, Object> jwk = publicJwk((ECPublicKey) pair.getPublic());
        jwk.put("d", b64u(((ECPrivateKey) pair.getPrivate()).getS(), 32));
        return jwk;
    }

    /** Build the ES256 private key from the session's private JWK (d in base64url). */
    private PrivateKey keyFromPrivateJwk(Map<String, Object> jwk) throws Exception {
        java.math.BigInteger d = new java.math.BigInteger(1, B64URL.decode(String.valueOf(jwk.get("d"))));
        return java.security.KeyFactory.getInstance("EC")
            .generatePrivate(new java.security.spec.ECPrivateKeySpec(d, com.fsp.shared.JwtService.p256Spec()));
    }

    /** Session state for PAR flow (stored in BFF session) */
    public record ParSessionState(
        String codeVerifier,
        String codeChallenge,
        String requestUri,
        String dpopPrivateKeyJwk,  // private session JWK (JSON) for DPoP signing
        String clientAssertion,    // assertion captured at PAR initiation (informational)
        Instant parExpiresAt,
        String state               // CSRF binding for the authorization response (RFC 6749 §10.12)
    ) implements java.io.Serializable {}

    /**
     * Step 1: Initiate PAR flow - generate PKCE, DPoP key, client_assertion, call /par.
     * Throws ParFlowInitiated carrying the authorization URL + session state.
     */
    public void startParFlow(String redirectUri, String scope) {
        // 1. Generate PKCE verifier (43-128 chars, high entropy)
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = computePkceChallenge(codeVerifier);

        // 2. Generate DPoP key pair for this session (EC P-256)
        KeyPair dpopKeyPair = generateP256();
        String dpopPrivateKeyJwk = toJson(privateJwk(dpopKeyPair));

        // 2b. Opaque state binding this browser session to the authorization response
        String oauthState = generateCodeVerifier();

        // 3. Fresh client_assertion for PAR
        String clientAssertion = createClientAssertion();

        // 4. Call PAR endpoint (snake_case body — the IdP controller binds a Map)
        Map<String, Object> parBody = new LinkedHashMap<>();
        parBody.put("grant_type", "authorization_code");
        parBody.put("code_challenge", codeChallenge);
        parBody.put("code_challenge_method", "S256");
        parBody.put("redirect_uri", redirectUri);
        parBody.put("response_type", "code");
        parBody.put("scope", scope);
        parBody.put("state", oauthState);

        var parResponse = rest.method(HttpMethod.POST)
            .uri("/idp/par")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + clientAssertion)
            .body(parBody)
            .retrieve()
            .toEntity(ParResponse.class);

        if (!parResponse.getStatusCode().is2xxSuccessful() || parResponse.getBody() == null) {
            throw new ApiProblemException("PAR_FAILED", "Pushed Authorization Request failed", 502);
        }

        ParResponse par = parResponse.getBody();
        String requestUri = par.requestUri();
        int expiresIn = par.expiresIn();

        ParSessionState state = new ParSessionState(
            codeVerifier, codeChallenge, requestUri, dpopPrivateKeyJwk, clientAssertion,
            Instant.now().plusSeconds(expiresIn), oauthState);

        // Minimal authorization URL (FAPI 2.0 §5.3.2): only client_id + request_uri
        String authzUrl = props.getAuthorizeUrlBase() + "/idp/authorize"
            + "?client_id=" + CLIENT_ID
            + "&request_uri=" + java.net.URLEncoder.encode(requestUri, java.nio.charset.StandardCharsets.UTF_8);

        throw new ParFlowInitiated(authzUrl, state);
    }

    /**
     * Step 2: Complete authorization code flow after callback.
     * Validates iss, exchanges code with fresh private_key_jwt + PKCE verifier + DPoP.
     */
    public TokenResponse completeAuthCodeFlow(String authCode, String iss, ParSessionState parState) {
        // 1. Validate iss (FAPI 2.0 §5.3.3.2) - prevents mix-up attacks
        if (!props.getIdpIssuer().equals(iss)) {
            throw new ApiProblemException("ISS_MISMATCH",
                "Authorization response issuer mismatch: expected " + props.getIdpIssuer() + ", got " + iss, 400);
        }

        // 2. DPoP proof for the token endpoint — htu as the IdP advertises it (issuer-qualified)
        String dpopProof = dpopProof(parState.dpopPrivateKeyJwk(),
            props.getIdpIssuer() + "/idp/token", "POST");

        // 3. Exchange code; a FRESH assertion (60s window), never the PAR-time one
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", authCode);
        body.put("code_verifier", parState.codeVerifier());
        body.put("redirect_uri", props.getLoginRedirectUri()); // must match PAR redirect_uri
        body.put("client_assertion", createClientAssertion());
        body.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");

        var tokenResponse = rest.method(HttpMethod.POST)
            .uri("/idp/token")
            .header("Content-Type", "application/json")
            .header("DPoP", dpopProof)
            .body(body)
            .retrieve()
            .toEntity(TokenResponse.class);

        if (!tokenResponse.getStatusCode().is2xxSuccessful() || tokenResponse.getBody() == null) {
            throw new ApiProblemException("TOKEN_EXCHANGE_FAILED", "Token exchange failed", 502);
        }

        return tokenResponse.getBody();
    }

    /**
     * refresh_token grant (FAPI 2.0): a fresh private_key_jwt + a DPoP proof by the SESSION's key,
     * so the new access token stays bound to the same key. Returns null when the IdP refuses
     * (revoked, expired, person no longer allowed) — the caller ends the session.
     */
    public TokenResponse refresh(String refreshToken, String dpopPrivateKeyJwk) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_assertion", createClientAssertion());
        body.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        try {
            var r = rest.method(HttpMethod.POST).uri("/idp/token")
                .header("Content-Type", "application/json")
                .header("DPoP", dpopProof(dpopPrivateKeyJwk, props.getIdpIssuer() + "/idp/token", "POST"))
                .body(body).retrieve().toEntity(TokenResponse.class);
            return r.getStatusCode().is2xxSuccessful() ? r.getBody() : null;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return null; // 4xx = the grant is refused; never logged with the token
        }
    }

    /** RFC 7009 revocation of the session's refresh token (logout trio). Best effort; never throws. */
    public void revoke(String refreshToken, String reason) {
        if (refreshToken == null) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", refreshToken);
        body.put("token_type_hint", "refresh_token");
        body.put("reason", reason);
        body.put("client_assertion", createClientAssertion());
        body.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        try {
            rest.method(HttpMethod.POST).uri("/idp/revoke").header("Content-Type", "application/json")
                .body(body).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("bff_refresh_revoke_failed reason={} error={}", reason, e.getClass().getSimpleName());
        }
    }

    /** Create DPoP proof JWT for a given HTTP request (RFC 9449 §4.2). */
    public String createDPoPProofForApi(String dpopPrivateKeyJwk, String htu, String htm) {
        return dpopProof(dpopPrivateKeyJwk, htu, htm);
    }

    // ========== Crypto / JWT helpers ==========

    /** client_assertion (private_key_jwt): ES256, public JWK embedded, aud = issuer string. */
    private String createClientAssertion() {
        try {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "ES256");
            header.put("typ", "JWT");
            header.put("jwk", publicJwk((ECPublicKey) clientKeyPair.getPublic()));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("iss", CLIENT_ID);
            payload.put("sub", CLIENT_ID);
            payload.put("aud", props.getIdpIssuer()); // FAPI 2.0: aud = issuer identifier as STRING
            payload.put("jti", UUID.randomUUID().toString());
            payload.put("exp", now + 60);
            payload.put("iat", now);

            return signedJwt(header, payload, clientKeyPair.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create client_assertion", e);
        }
    }

    /** RFC 9449 proof: header {ES256, typ dpop+jwt, jwk public-only}; payload {htu, htm, iat, jti}. */
    private String dpopProof(String privateJwkJson, String htu, String htm) {
        try {
            Map<String, Object> jwk = mapper.readValue(privateJwkJson, Map.class);

            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "ES256");
            header.put("typ", "dpop+jwt");
            header.put("jwk", stripPrivate(jwk));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("htu", htu);
            payload.put("htm", htm);
            payload.put("jti", UUID.randomUUID().toString());
            payload.put("iat", Instant.now().getEpochSecond());

            return signedJwt(header, payload, keyFromPrivateJwk(jwk));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create DPoP proof", e);
        }
    }

    /** Remove any private key material (d) from a JWK map. */
    private static Map<String, Object> stripPrivate(Map<String, Object> jwk) {
        Map<String, Object> pub = new LinkedHashMap<>(jwk);
        pub.remove("d");
        pub.remove("p");
        pub.remove("q");
        pub.remove("dp");
        pub.remove("dq");
        pub.remove("qi");
        return pub;
    }

    private String signedJwt(Map<String, Object> header, Map<String, Object> payload, PrivateKey key)
        throws Exception {
        String h = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(header));
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(payload));
        String signingInput = h + "." + p;
        String s = signEs256(key, signingInput);
        return signingInput + "." + s;
    }

    private String toJson(Map<String, Object> jwk) {
        try {
            return mapper.writeValueAsString(jwk);
        } catch (Exception e) {
            throw new IllegalStateException("jwk serialization failed", e);
        }
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String computePkceChallenge(String verifier) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // Exception to carry PAR flow state back to controller
    public static class ParFlowInitiated extends RuntimeException {
        private final String authzUrl;
        private final ParSessionState state;
        public ParFlowInitiated(String authzUrl, ParSessionState state) {
            this.authzUrl = authzUrl;
            this.state = state;
        }
        public String getAuthzUrl() { return authzUrl; }
        public ParSessionState getState() { return state; }
    }

    // IdP DTOs (local copies to avoid circular dependency)
    private record ParResponse(String requestUri, int expiresIn) {}
}