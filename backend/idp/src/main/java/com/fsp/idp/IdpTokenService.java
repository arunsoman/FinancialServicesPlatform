package com.fsp.idp;

import com.fsp.shared.JwtService;
import com.fsp.shared.PkiKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.math.BigInteger;

/**
 * Token issuance for the NATIVE FSP IdP (default provider). Third-party federation hooks in at the
 * same seam: policy says THIRD_PARTY → a federation client (OIDC, FAPI2 slice) takes over and
 * (issuer,subject) links land in idp_identities — this service never changes.
 * Logging discipline per AGENTS.md §2c: INFO milestones, WARN on negative 4xx paths.
 */
@Service
public class IdpTokenService {

    private static final Logger log = LoggerFactory.getLogger(IdpTokenService.class);

    private final PPersonRepository persons;
    private final PTenantRepository tenants;
    private final IdpConfigRepository configs;
    private final IdpAuthService auth;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final JwtService jwt;

    private final LoginThrottle throttle;
    private final long ttlSeconds;
    private final IdpProperties props;
    private final RefreshTokens refreshTokens;

    public IdpTokenService(PPersonRepository persons, PTenantRepository tenants,
                           IdpConfigRepository configs, IdpAuthService auth, JdbcTemplate jdbc,
                           IdpProperties props, LoginThrottle throttle, RefreshTokens refreshTokens) {
        this.refreshTokens = refreshTokens;
        this.persons = persons;
        this.tenants = tenants;
        this.configs = configs;
        this.auth = auth;
        this.jdbc = jdbc;
        this.ttlSeconds = props.getAccessTokenTtlSeconds();
        this.props = props;
        this.throttle = throttle;
        this.jwt = new JwtService(resolveSigningKey(props.getSigningKeyPath()), props.getIssuer());
        if (!jwt.canSign()) {
            throw new IllegalStateException("idp_signing_key_missing run 'make pki' (idp-signing.pem)");
        }
    }

    /** Signing key lookup via the shared relative-only PkiKeyResolver (launcher-cwd-independent). */
    private static java.nio.file.Path resolveSigningKey(String configured) {
        return PkiKeyResolver.resolve(configured, "idp-signing.pem");
    }

    public IdpDtos.TokenResponse grantPassword(IdpDtos.TokenGrant grant) {
        if (grant.grantType() == null || !grant.grantType().equals("password")) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "UNSUPPORTED_GRANT", "grant_type must be 'password' in the native slice", 400));
        }
        if (grant.username() == null || grant.username().isBlank()
            || grant.password() == null || grant.password().isEmpty()) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "username and password are required", 400));
        }

        PPerson person = persons.findByUsername(grant.username()).orElse(null);
        throttle.check(grant.username());
        if (person == null || !bcrypt.matches(grant.password(), person.passwordHash)) {
            throttle.failure(grant.username());
            log.warn("idp_login_failed username={} reason=invalid_credentials", grant.username());
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_CREDENTIALS", "Invalid username or password", 401));
        }
        // status gates first — the throttle only clears on FULL sign-in success
        if (!"ACTIVE".equals(person.status)) {
            log.warn("idp_login_failed username={} reason=person_inactive", grant.username());
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "PERSON_INACTIVE", "Account is not active", 403));
        }
        // V36: an invited person signs in only after activating (own password, contact proven).
        // Unreachable with a correct password in practice (born with a random secret) — the
        // explicit gate keeps it true even if a password were ever set another way.
        if (person.primaryContact != null && person.contactVerifiedAt == null) {
            log.warn("idp_login_failed username={} reason=activation_pending", grant.username());
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "ACTIVATION_PENDING", "Activate your account from the link or code we sent you", 403));
        }
        throttle.success(grant.username());
        // F-8: persons born with the bootstrap password must set their own at first
        // sign-in — the hosted surface (authorize callback) intercepts this code and
        // renders the change page; a direct token grant simply gets the 403.
        if (person.mustChangePassword) {
            log.info("idp_password_change_required username={}", grant.username());
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "PASSWORD_CHANGE_REQUIRED", "Choose your own password to continue", 403));
        }

        PTenant tenant = tenants.findById(person.tenantId)
            .orElseThrow(() -> new IdpProblemException(new IdpDtos.IdpProblem(
                "TENANT_MISSING", "Identity is not linked to a tenant", 409)));
        if ("SUSPENDED".equals(tenant.status) || "TERMINATED".equals(tenant.status)) {
            log.warn("idp_login_failed username={} reason=tenant_status status={}",
                grant.username(), tenant.status);
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "TENANT_INACTIVE", "Tenant is " + tenant.status, 403));
        }

        String provider = configs.findByTenantId(tenant.id).map(c -> c.provider).orElse("FSP_NATIVE");
        if ("THIRD_PARTY".equals(provider)) {
            log.warn("idp_login_failed username={} reason=federation_pending tenant={}",
                grant.username(), tenant.id);
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "IDP_EXTERNAL_FEDERATION_PENDING",
                "Tenant uses a third-party IdP; federated login arrives with the FAPI2 slice", 503));
        }

        // V30 MFA: a person with an enrolled second factor must pass the channel OTP step here —
        // the code itself arrives through the notification EMAIL/SMS transports (chapter 02 §7).
        auth.requireSecondFactor(person.id, grant.otp());

        List<String> roles = roleKeys(person.id);
        Instant now = Instant.now();
        String clientId = grant.clientId() == null || grant.clientId().isBlank()
            ? "fsp-confidence-client" : grant.clientId();
        String token = jwt.sign(new JwtService.Claims(
            person.id.toString(), tenant.id.toString(), person.username, clientId,
            roles, now, now.plusSeconds(ttlSeconds), JwtService.DEFAULT_AUDIENCE), clientId, JwtService.DEFAULT_AUDIENCE);

        IdpDtos.TokenClaims claims = new IdpDtos.TokenClaims(
            person.id.toString(), person.username, person.fullName, tenant.id.toString(),
            tenant.legalName, tenant.type, roles);
        log.info("idp_token_issued username={} tenant={} roles={} expiresIn={}",
            grant.username(), tenant.id, roles.size(), ttlSeconds);
        return new IdpDtos.TokenResponse(token, "Bearer", ttlSeconds, claims);
    }

    /** Self-service MFA surface (V30): resolve the caller from its own bearer token —
     *  the same RS256/issuer/audience discipline as introspect. */
    public UUID personFromBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_TOKEN", "Bearer token required", 401));
        }
        JwtService.Claims claims = jwt.verify(authorization.substring(7).trim(),
            jwt.jwksMap(jwt.jwksJson()), jwt.issuer(), JwtService.DEFAULT_AUDIENCE);
        if (claims == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_TOKEN", "Bearer token invalid or expired", 401));
        }
        return UUID.fromString(claims.userId());
    }

    public IdpDtos.IntrospectionResponse introspect(String token) {
        var claims = jwt.verify(token, jwt.jwksMap(jwt.jwksJson()), jwt.issuer(), JwtService.DEFAULT_AUDIENCE);
        if (claims == null) {
            return new IdpDtos.IntrospectionResponse(false, null, null, null, null, null);
        }
        return new IdpDtos.IntrospectionResponse(true, claims.userId(), claims.tenantId(),
            claims.username(), jwt.issuer(), claims.expiresAt().getEpochSecond());
    }

    public String jwksJson() {
        return jwt.jwksJson();
    }

    public IdpDtos.PolicyView policy(UUID tenantId) {
        IdpConfig cfg = configs.findByTenantId(tenantId).orElse(null);
        if (cfg == null) {
            return new IdpDtos.PolicyView(tenantId.toString(), "FSP_NATIVE", null,
                JwtService.DEFAULT_AUDIENCE, null, null, 0);
        }
        return new IdpDtos.PolicyView(cfg.tenantId.toString(), cfg.provider, cfg.issuerUrl,
            cfg.audience, cfg.clientId, cfg.jwksUri, cfg.policyVersion);
    }

    public IdpDtos.PolicyView updatePolicy(UUID tenantId, IdpDtos.PolicyUpdate update, UUID updatedBy) {
        if (update.provider() == null
            || (!update.provider().equals("FSP_NATIVE") && !update.provider().equals("THIRD_PARTY"))) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_PROVIDER", "provider must be FSP_NATIVE or THIRD_PARTY", 400));
        }
        if ("THIRD_PARTY".equals(update.provider())
            && (update.issuerUrl() == null || update.issuerUrl().isBlank())) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_PROVIDER", "third_party requires issuer_url", 400));
        }
        IdpConfig cfg = configs.findByTenantId(tenantId).orElseGet(() -> {
            IdpConfig fresh = new IdpConfig();
            fresh.tenantId = tenantId;
            fresh.policyVersion = 0;
            return fresh;
        });
        cfg.provider = update.provider();
        cfg.issuerUrl = update.issuerUrl();
        cfg.audience = update.audience();
        cfg.clientId = update.clientId();
        cfg.jwksUri = update.jwksUri();
        cfg.policyVersion = cfg.policyVersion + 1;
        cfg.updatedBy = updatedBy;
        cfg.updatedAt = Instant.now();
        configs.save(cfg);
        log.info("idp_policy_updated tenant={} provider={} version={}", tenantId, cfg.provider, cfg.policyVersion);
        return policy(tenantId);
    }

    // ========== FAPI 2.0: Authorization Code Flow with PKCE, DPoP, private_key_jwt ==========

    /**
     * Token exchange for authorization code grant (FAPI 2.0).
     * Validates: PKCE verifier, client_assertion (private_key_jwt), DPoP proof.
     * Issues access token with cnf.jkt claim binding to client's DPoP key.
     */
    public IdpDtos.TokenResponse exchangeAuthCode(IdpDtos.TokenExchangeRequest request,
                                                   String dpopHeader,
                                                   ParService.AuthCodeData authCodeData) {
        // 1. Validate grant_type
        if (request.grantType() == null || !request.grantType().equals("authorization_code")) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "UNSUPPORTED_GRANT", "grant_type must be 'authorization_code'", 400));
        }

        // 2. Validate client_assertion (private_key_jwt)
        String clientId = validateClientAssertion(request.clientAssertion());
        if (clientId == null || !clientId.equals(authCodeData.clientId())) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_CLIENT", "Invalid client_assertion", 401));
        }

        // 3. Validate DPoP proof
        String jkt = validateDPoPProof(dpopHeader, props.getIssuer() + "/idp/token", "POST");
        if (jkt == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_DPOP_PROOF", "DPoP proof validation failed", 400));
        }

        // 4. The PAR service already validated PKCE and consumed the auth code
        // authCodeData contains personId, tenantId, scope

        PPerson person = persons.findById(authCodeData.personId()).orElse(null);
        if (person == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_GRANT", "Person not found", 400));
        }

        PTenant tenant = tenants.findById(authCodeData.tenantId())
            .orElseThrow(() -> new IdpProblemException(new IdpDtos.IdpProblem(
                "TENANT_MISSING", "Tenant not found", 409)));

        List<String> roles = roleKeys(person.id);
        Instant now = Instant.now();

        // 5. Issue access token with cnf.jkt claim (thumbprint of client's DPoP key)
        String token = jwt.signWithCnf(new JwtService.Claims(
            person.id.toString(), tenant.id.toString(), person.username, clientId,
            roles, now, now.plusSeconds(ttlSeconds), JwtService.DEFAULT_AUDIENCE), clientId, JwtService.DEFAULT_AUDIENCE, jkt);

        IdpDtos.TokenClaims claims = new IdpDtos.TokenClaims(
            person.id.toString(), person.username, person.fullName, tenant.id.toString(),
            tenant.legalName, tenant.type, roles);
        // 6. Refresh token (FAPI 2.0): bound to this client and this DPoP key, never rotated
        String refreshToken = refreshTokens.issue(person.id, tenant.id, clientId, jkt, props.getRefreshTokenTtlSeconds());
        log.info("idp_token_exchanged username={} tenant={} clientId={} jkt={} expiresIn={} refreshTtl={}",
            person.username, tenant.id, clientId, jkt, ttlSeconds, props.getRefreshTokenTtlSeconds());
        return new IdpDtos.TokenResponse(token, "DPoP", ttlSeconds, claims, refreshToken);
    }

    /**
     * refresh_token grant (FAPI 2.0 / RFC 6749 §6 / RFC 9449 §5). Requires the SAME client
     * (private_key_jwt) and a fresh DPoP proof by the SAME key the refresh token is bound to;
     * the person and their organization must still be allowed to sign in, and roles are read
     * again. Issues a new DPoP-bound access token; the refresh token is NOT rotated (§5.3.2.1)
     * and is not returned again.
     */
    public IdpDtos.TokenResponse refresh(String refreshToken, String clientAssertion, String dpopHeader) {
        String clientId = validateClientAssertion(clientAssertion);
        if (clientId == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem("INVALID_CLIENT", "Invalid client_assertion", 401));
        }
        String jkt = validateDPoPProof(dpopHeader, props.getIssuer() + "/idp/token", "POST");
        if (jkt == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem("INVALID_DPOP_PROOF", "DPoP proof validation failed", 400));
        }
        RefreshTokens.Stored rt = refreshTokens.find(refreshToken).orElse(null);
        String refusal = rt == null ? "unknown"
            : rt.revokedAt() != null ? "revoked"
            : !Instant.now().isBefore(rt.expiresAt()) ? "expired"
            : !rt.clientId().equals(clientId) ? "client_mismatch"
            : !rt.jkt().equals(jkt) ? "dpop_key_mismatch"
            : null;
        if (refusal != null) {
            log.warn("idp_refresh_refused reason={} clientId={}", refusal, clientId);
            throw new IdpProblemException(new IdpDtos.IdpProblem("INVALID_GRANT", "Refresh token is not valid", 400));
        }
        PPerson person = persons.findById(rt.personId()).orElse(null);
        PTenant tenant = tenants.findById(rt.tenantId()).orElse(null);
        String blocked = person == null ? "person_missing"
            : !"ACTIVE".equals(person.status) ? "person_inactive"
            : tenant == null ? "tenant_missing"
            : ("SUSPENDED".equals(tenant.status) || "TERMINATED".equals(tenant.status)) ? "tenant_" + tenant.status.toLowerCase()
            : null;
        if (blocked != null) {
            refreshTokens.revoke(refreshToken, clientId, blocked);
            log.warn("idp_refresh_refused reason={} personId={}", blocked, rt.personId());
            throw new IdpProblemException(new IdpDtos.IdpProblem("INVALID_GRANT", "Sign in again", 400));
        }
        List<String> roles = roleKeys(person.id);
        Instant now = Instant.now();
        String token = jwt.signWithCnf(new JwtService.Claims(
            person.id.toString(), tenant.id.toString(), person.username, clientId,
            roles, now, now.plusSeconds(ttlSeconds), JwtService.DEFAULT_AUDIENCE), clientId, JwtService.DEFAULT_AUDIENCE, jkt);
        refreshTokens.touch(rt.id());
        IdpDtos.TokenClaims claims = new IdpDtos.TokenClaims(
            person.id.toString(), person.username, person.fullName, tenant.id.toString(),
            tenant.legalName, tenant.type, roles);
        log.info("idp_token_refreshed username={} tenant={} clientId={} jkt={} expiresIn={}",
            person.username, tenant.id, clientId, jkt, ttlSeconds);
        return new IdpDtos.TokenResponse(token, "DPoP", ttlSeconds, claims, null);
    }

    /** RFC 7009 revocation by the client that holds the token; always answers OK (no oracle). */
    public void revoke(String refreshToken, String clientAssertion, String reason) {
        String clientId = validateClientAssertion(clientAssertion);
        if (clientId == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem("INVALID_CLIENT", "Invalid client_assertion", 401));
        }
        if (refreshToken == null || refreshToken.isBlank()) return;
        String why = reason == null ? "" : reason.replaceAll("[^a-z_]", "");
        if (why.isEmpty()) why = "logout";
        if (why.length() > 32) why = why.substring(0, 32);
        boolean revoked = refreshTokens.revoke(refreshToken, clientId, why);
        log.info("idp_refresh_revoked clientId={} revoked={} reason={}", clientId, revoked, why);
    }

    /**
     * Validate DPoP proof JWT per RFC 9449.
     * Returns the JWK thumbprint (jkt) if valid, null otherwise.
     */
    /** DPoP proof jti replay cache (RFC 9449 §11.1) — proofs are single-use at the token endpoint. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> DPOP_JTI =
        new java.util.concurrent.ConcurrentHashMap<>();

    private String validateDPoPProof(String dpopHeader, String expectedHtu, String expectedHtm) {
        if (dpopHeader == null || dpopHeader.isBlank()) {
            log.warn("dpop_missing");
            return null;
        }

        String[] parts = dpopHeader.split("\\.");
        if (parts.length != 3) {
            log.warn("dpop_invalid_format");
            return null;
        }

        try {
            // Decode header
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            var header = new com.fasterxml.jackson.databind.ObjectMapper().readTree(headerJson);

            // Verify typ and alg
            if (!"dpop+jwt".equals(header.path("typ").asText())) {
                log.warn("dpop_invalid_typ");
                return null;
            }
            if (!"ES256".equals(header.path("alg").asText()) && !"PS256".equals(header.path("alg").asText())) {
                log.warn("dpop_invalid_alg");
                return null;
            }

            // Decode payload
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson);

            // Validate htu (HTTP URI)
            String htu = payload.path("htu").asText();
            if (!expectedHtu.equals(htu)) {
                log.warn("dpop_htu_mismatch expected={} actual={}", expectedHtu, htu);
                return null;
            }

            // Validate htm (HTTP method)
            String htm = payload.path("htm").asText();
            if (!expectedHtm.equalsIgnoreCase(htm)) {
                log.warn("dpop_htm_mismatch expected={} actual={}", expectedHtm, htm);
                return null;
            }

            // Validate jti (replay prevention - would need server-side cache in production)
            String jti = payload.path("jti").asText();
            if (jti == null || jti.isBlank()) {
                log.warn("dpop_missing_jti");
                return null;
            }
            if (DPOP_JTI.putIfAbsent(jti, Instant.now().getEpochSecond()) != null) {
                log.warn("dpop_replay_detected");
                return null;
            }
            if (DPOP_JTI.size() > 10_000) {
                long cutoff = Instant.now().getEpochSecond() - 600;
                DPOP_JTI.entrySet().removeIf(e -> e.getValue() < cutoff);
            }

            // Validate iat (issued at) - within acceptable clock skew
            long iat = payload.path("iat").asLong();
            long now = Instant.now().getEpochSecond();
            if (iat > now + 10 || iat < now - 300) { // FAPI 2.0: 0-10s future, reasonable past
                log.warn("dpop_iat_out_of_range iat={} now={}", iat, now);
                return null;
            }

            // Public JWK lives in the JWT header per RFC 9449 §4.2 (never in the payload)
            var jwk = header.path("jwk");
            if (jwk.isMissingNode()) {
                log.warn("dpop_missing_header_jwk");
                return null;
            }
            if (!jwk.path("d").isMissingNode()) {
                log.warn("dpop_private_key_in_jwk");
                return null;
            }

            // Compute JWK thumbprint (jkt) per RFC 7638
            String jkt = computeJwkThumbprint(jwk);

            // Verify signature
            if (!verifyDPoPSignature(parts[0], parts[1], parts[2], jwk)) {
                log.warn("dpop_signature_invalid");
                return null;
            }

            return jkt;
        } catch (Exception e) {
            log.warn("dpop_validation_error", e);
            return null;
        }
    }

    private String computeJwkThumbprint(com.fasterxml.jackson.databind.JsonNode jwk) throws Exception {
        return JwtService.jwkThumbprint(jwk);
    }

    private boolean verifyDPoPSignature(String headerB64, String payloadB64, String signatureB64,
                                         com.fasterxml.jackson.databind.JsonNode jwk) throws Exception {
        // ES256 over JWS signing input, raw r||s (P1363) — shared with gateway for parity
        return JwtService.verifyEs256(jwk, headerB64 + "." + payloadB64, signatureB64);
    }

    private String validateClientAssertion(String clientAssertion) {
        // ES256 assertion with embedded public JWK, bound to the issuer (FAPI 2.0)
        String clientId = ClientAssertions.validate(clientAssertion, props.getIssuer());
        if (clientId == null) {
            log.warn("client_assertion_validation_failed");
        }
        return clientId;
    }

    /** FAPI 2.0 machine-plane: client_credentials + private_key_jwt + DPoP.
     * Issues a DPoP-bound machine token (typ=machine, sub=client:<client_id>).
     * No refresh tokens; client re-authenticates per request (FAPI §5.3.2). */
    public IdpDtos.TokenResponse grantClientCredentials(IdpDtos.ClientCredentialsGrant grant,
                                                        String dpopHeader) {
        if (grant.grantType() == null || !grant.grantType().equals("client_credentials")) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "UNSUPPORTED_GRANT", "grant_type must be 'client_credentials'", 400));
        }
        if (grant.clientId() == null || grant.clientId().isBlank()) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "client_id is required", 400));
        }
        if (grant.clientAssertion() == null || grant.clientAssertion().isBlank()) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_REQUEST", "client_assertion is required", 400));
        }

        // 1. Load the client from the registry (unknown client → 401, never a 500)
        java.util.Map<String, Object> clientRow;
        try {
            clientRow = jdbc.queryForMap("""
                SELECT client_id, client_type, status, owner_tenant_id, token_ttl_s, jwks::text AS jwks
                FROM oauth_clients WHERE client_id = ?""", grant.clientId());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            log.warn("client_credentials_failed clientId={} reason=unknown_client", grant.clientId());
            throw new IdpProblemException(new IdpDtos.IdpProblem("INVALID_CLIENT", "Invalid client", 401));
        }

        // 2. private_key_jwt against the client's REGISTERED key (aud = issuer string, single-use jti)
        String assertedClientId = ClientAssertions.validateRegistered(grant.clientAssertion(), props.getIssuer(),
            grant.clientId(), (String) clientRow.get("jwks"));
        if (assertedClientId == null) {
            log.warn("client_credentials_failed clientId={} reason=invalid_client_assertion", grant.clientId());
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_CLIENT", "Invalid client_assertion", 401));
        }
        String status = (String) clientRow.get("status");
        if (!"ACTIVE".equals(status)) {
            log.warn("client_credentials_failed clientId={} reason=client_status status={}",
                grant.clientId(), status);
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "CLIENT_INACTIVE", "Client is " + status, 403));
        }
        String clientType = (String) clientRow.get("client_type");
        if (!"MACHINE".equals(clientType)) {
            log.warn("client_credentials_failed clientId={} reason=not_machine type={}",
                grant.clientId(), clientType);
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_CLIENT", "Client is not a machine client", 403));
        }
        UUID ownerTenantId = UUID.fromString(clientRow.get("owner_tenant_id").toString());
        long ttl = ((Number) clientRow.get("token_ttl_s")).longValue();

        // 3. Validate DPoP proof (sender constraint: token bound to client's DPoP key)
        String jkt = validateDPoPProof(dpopHeader, props.getIssuer() + "/idp/token", "POST");
        if (jkt == null) {
            throw new IdpProblemException(new IdpDtos.IdpProblem(
                "INVALID_DPOP_PROOF", "DPoP proof validation failed", 400));
        }

        // 4. Issue machine token: sub = "client:<client_id>", typ inferred by sub prefix
        Instant now = Instant.now();
        String token = jwt.signWithCnf(new JwtService.Claims(
            "client:" + grant.clientId(), ownerTenantId.toString(), null, grant.clientId(),
            List.of(), now, now.plusSeconds(ttl), JwtService.DEFAULT_AUDIENCE),
            grant.clientId(), JwtService.DEFAULT_AUDIENCE, jkt);

        IdpDtos.TokenClaims claims = new IdpDtos.TokenClaims(
            "client:" + grant.clientId(), null, null, ownerTenantId.toString(), null, null, List.of());
        log.info("idp_client_credentials clientId={} tenant={} jkt={} expiresIn={}",
            grant.clientId(), ownerTenantId, jkt, ttl);
        return new IdpDtos.TokenResponse(token, "DPoP", ttl, claims);
    }

    private List<String> roleKeys(UUID personId) {
        try {
            return jdbc.queryForList("""
                SELECT DISTINCT r.key FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE ur.person_id = ?""", String.class, personId);
        } catch (DataAccessException e) {
            log.warn("idp_roles_lookup_failed person={} falling_back_to_empty", personId);
            return new ArrayList<>();
        }
    }
}