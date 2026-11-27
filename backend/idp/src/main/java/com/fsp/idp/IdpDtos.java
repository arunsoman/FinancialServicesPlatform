package com.fsp.idp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire DTOs of the IdP (records grouped in a final container — AGENTS.md §2b; never sealed). */
public final class IdpDtos {
    private IdpDtos() {}

    // --- Token endpoint (existing ROPC - will be deprecated) ---
    public record TokenGrant(String grantType, String username, String password, String clientId,
                             /** V30 MFA: the second-factor code when the person enrolled one. */
                             String otp) {}

    // --- FAPI 2.0 machine-plane: client_credentials + private_key_jwt + DPoP ---
    public record ClientCredentialsGrant(String grantType, String clientId, String clientAssertion,
                                         String clientAssertionType, String scope) {}

    // --- PAR (Pushed Authorization Request) ---
    public record ParRequest(
        String grantType,
        String codeChallenge,
        String codeChallengeMethod,
        String redirectUri,
        String responseType,
        String scope,
        String authorizationDetails,
        String resource,
        String clientAssertion,
        String clientAssertionType,
        String state
    ) {}

    public record ParResponse(String requestUri, int expiresIn) {}

    // --- Authorization endpoint ---
    public record AuthzRequest(String clientId, String requestUri) {}

    public record AuthzResponse(String code, String state, String iss) {}

    // --- Token exchange (auth code + PKCE + DPoP + private_key_jwt) ---
    public record TokenExchangeRequest(
        String grantType,
        String code,
        String codeVerifier,
        String redirectUri,
        String clientAssertion,
        String clientAssertionType
    ) {}

    public record TokenClaims(String userId, String username, String fullName, String tenantId,
                              String tenantName, String tenantType, List<String> roles) {}

    /** refreshToken: only on the authorization_code grant (never rotated, never sent to a browser). */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, TokenClaims claims, String refreshToken) {
        public TokenResponse(String accessToken, String tokenType, long expiresIn, TokenClaims claims) {
            this(accessToken, tokenType, expiresIn, claims, null);
        }
    }

    // --- DPoP ---
    public record DPoPProof(String htu, String htm, String jti, long iat, String cnf) {}

    // --- Introspection ---
    public record IntrospectionResponse(boolean active, String sub, String tid, String uname,
                                        String iss, Long exp) {}

    // --- Policy ---
    public record PolicyView(String tenantId, String provider, String issuerUrl, String audience,
                             String clientId, String jwksUri, int policyVersion) {}

    public record PolicyUpdate(String provider, String issuerUrl, String audience,
                               String clientId, String jwksUri) {}

    public record IdpProblem(String code, String detail, int status) {}

    public record IdentityLink(UUID identityId, String issuer, String subject, UUID personId) {}

    public record EffectivePolicy(String tenantId, String provider, String issuerUrl,
                                  String audience, String clientId, String jwksUri, int policyVersion) {}

    /** Normalized actor identity as re-issued by the gateway for internal hops (future policy enforcement). */
    public record ActorStamp(UUID personId, String username, UUID tenantId, Instant at) {}

    // --- Internal: OAuth client management (core -> IdP) ---
    public record ClientView(String clientId, String clientType, String jwks,
                             List<String> grantTypes, String status, UUID ownerTenantId,
                             int tokenTtlSeconds) {}

    public record ClientCreate(String clientId, String jwks, UUID ownerTenantId,
                               Integer tokenTtlSeconds) {}

    public record ClientUpdate(String status) {}
}
