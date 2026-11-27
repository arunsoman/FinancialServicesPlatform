package com.fsp.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Actor assertion — the gateway's short-lived signed statement of WHO a person→plugin
 * request is for (plugin-platform-plan §4.4-4.5). It replaces forwarding the person's
 * access token: the token never leaves the gateway.
 *
 * Wire form: compact JWS, header {alg: ES256, typ: fsp-actor+jwt, kid}, payload
 * { iss: fsp-gateway, aud: "plugin:<code>", sub: personId, tid, uname, clid, op, perm,
 *   target, corr, htm, htu, jti, iat, exp [, act, home] } with a 60 s lifetime.
 * {@code act}/{@code home} are present only while the person acts as a tenant
 * (plans/act-as-tenant-plan.md): {@code tid} is then the acted-as tenant, {@code act} the
 * act-as session core verified for the gateway, {@code home} the person's own tenant.
 *
 * The gateway holds the private key ({@link #issuer}); core and plugins hold only the
 * public JWKS ({@link #verifier}). Verification checks the SIGNATURE FIRST, then
 * iss / aud (exact string) / exp / iat / jti use budget / optional htm+htu binding.
 *
 * No dependency on FSP core exception types: failures throw {@link Rejected} with a stable
 * code that callers map to their own error model.
 */
public final class ActorAssertion {

    public static final String ISSUER = "fsp-gateway";
    public static final String TYP = "fsp-actor+jwt";
    public static final long TTL_SECONDS = 60;
    private static final long CLOCK_SKEW_SECONDS = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ECPrivateKey signingKey;
    private final Map<String, Object> publicJwk;
    private final String kid;
    private final Function<String, JsonNode> keyResolver;
    private final int maxUses;

    /** jti -> uses so far (pruned after expiry). */
    private final ConcurrentHashMap<String, long[]> uses = new ConcurrentHashMap<>();

    private ActorAssertion(ECPrivateKey signingKey, ECPublicKey publicKey,
                           Function<String, JsonNode> keyResolver, int maxUses) {
        this.signingKey = signingKey;
        this.publicJwk = publicKey == null ? null : jwkOf(publicKey);
        this.kid = publicJwk == null ? null : JwtService.jwkThumbprint(MAPPER.valueToTree(publicJwk));
        this.keyResolver = keyResolver;
        this.maxUses = maxUses;
    }

    /** Gateway: signs assertions. Its public key is published via {@link #publicJwks()}. */
    public static ActorAssertion issuer(ECPrivateKey privateKey, ECPublicKey publicKey) {
        return new ActorAssertion(privateKey, publicKey, null, 0);
    }

    /**
     * Core / plugins: verify only. {@code keyResolver} maps a kid to the gateway's public
     * JWK (null when unknown — the caller may refresh its JWKS cache inside the resolver).
     * {@code maxUses}: how many times one assertion may be presented to THIS verifier
     * (plugin inbound = 1; core on-behalf = a small saga budget).
     */
    public static ActorAssertion verifier(Function<String, JsonNode> keyResolver, int maxUses) {
        if (maxUses < 1) throw new IllegalArgumentException("maxUses must be ≥ 1");
        return new ActorAssertion(null, null, keyResolver, maxUses);
    }

    /** What a person→plugin request is allowed to do, as decided by core. */
    public record Subject(String personId, String tenantId, String username, String clientId,
                          String operationId, String permission, String targetTenantId,
                          String correlationId, String actSessionId, String homeTenantId) {
        /** Not acting: the person works in their own tenant. */
        public Subject(String personId, String tenantId, String username, String clientId,
                       String operationId, String permission, String targetTenantId, String correlationId) {
            this(personId, tenantId, username, clientId, operationId, permission, targetTenantId,
                correlationId, null, null);
        }
    }

    /** Verified result of an assertion check. */
    public record VerifiedActor(String personId, String tenantId, String username,
                                String clientId, String operationId, String permission,
                                String targetTenantId, String correlationId, String jti,
                                String audience, String actSessionId, String homeTenantId) {
        /** True when the gateway stated an act-as session (re-verified by core before use). */
        public boolean acting() {
            return actSessionId != null && !actSessionId.isBlank();
        }
    }

    /** A verification failure with a stable code (ASSERTION_*). */
    public static final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code, String detail) {
            super(code + ": " + detail);
            this.code = code;
        }
        public String code() { return code; }
    }

    // ------------------------------------------------------------------ issue

    public String mint(Subject s, String audience, String httpMethod, String requestPath) {
        if (signingKey == null) throw new IllegalStateException("verifier cannot mint assertions");
        Instant now = Instant.now();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "ES256");
        header.put("typ", TYP);
        header.put("kid", kid);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("iss", ISSUER);
        p.put("aud", audience);
        p.put("sub", nz(s.personId()));
        p.put("tid", nz(s.tenantId()));
        p.put("uname", nz(s.username()));
        p.put("clid", nz(s.clientId()));
        p.put("op", nz(s.operationId()));
        p.put("perm", nz(s.permission()));
        p.put("target", nz(s.targetTenantId()));
        p.put("corr", nz(s.correlationId()));
        if (s.actSessionId() != null && !s.actSessionId().isBlank()) {
            p.put("act", s.actSessionId());
            p.put("home", nz(s.homeTenantId()));
        }
        p.put("htm", httpMethod.toUpperCase());
        p.put("htu", requestPath);
        p.put("jti", UUID.randomUUID().toString());
        p.put("iat", now.getEpochSecond());
        p.put("exp", now.plusSeconds(TTL_SECONDS).getEpochSecond());
        try {
            String input = B64.encodeToString(MAPPER.writeValueAsBytes(header)) + "."
                + B64.encodeToString(MAPPER.writeValueAsBytes(p));
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(signingKey);
            sig.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + B64.encodeToString(JwtService.ecdsaDerToJws(sig.sign(), 32));
        } catch (Exception e) {
            throw new IllegalStateException("assertion signing failed", e);
        }
    }

    /** The gateway's public key set: {"keys":[{kty,crv,x,y,use,alg,kid}]}. */
    public Map<String, Object> publicJwks() {
        if (publicJwk == null) throw new IllegalStateException("no public key");
        Map<String, Object> k = new LinkedHashMap<>(publicJwk);
        k.put("use", "sig");
        k.put("alg", "ES256");
        k.put("kid", kid);
        return Map.of("keys", java.util.List.of(k));
    }

    public String kid() { return kid; }

    // ----------------------------------------------------------------- verify

    /**
     * Verify an assertion. {@code httpMethod}/{@code requestPath} non-null = the assertion
     * must be bound to exactly this request (the plugin's inbound check); null = not bound
     * to this hop (core's on-behalf check, where the binding is to the plugin request).
     */
    public VerifiedActor verify(String assertion, String expectedAudience,
                                String httpMethod, String requestPath) {
        if (keyResolver == null) throw new IllegalStateException("issuer-only instance");
        if (assertion == null || assertion.isBlank()) {
            throw new Rejected("ASSERTION_MISSING", "actor assertion required");
        }
        String[] parts = assertion.split("\\.");
        if (parts.length != 3) throw new Rejected("ASSERTION_INVALID", "malformed assertion");

        JsonNode header;
        JsonNode p;
        try {
            header = MAPPER.readTree(B64D.decode(parts[0]));
            p = MAPPER.readTree(B64D.decode(parts[1]));
        } catch (Exception e) {
            throw new Rejected("ASSERTION_INVALID", "undecodable assertion");
        }
        if (!"ES256".equals(header.path("alg").asText()) || !TYP.equals(header.path("typ").asText())) {
            throw new Rejected("ASSERTION_INVALID", "unexpected alg/typ");
        }
        JsonNode jwk = keyResolver.apply(header.path("kid").asText());
        if (jwk == null) throw new Rejected("ASSERTION_INVALID", "unknown signing key");
        if (!JwtService.verifyEs256(jwk, parts[0] + "." + parts[1], parts[2])) {
            throw new Rejected("ASSERTION_INVALID", "bad signature");
        }

        if (!ISSUER.equals(p.path("iss").asText())) throw new Rejected("ASSERTION_BAD_ISS", "issuer");
        JsonNode aud = p.path("aud");
        if (!aud.isTextual() || !aud.asText().equals(expectedAudience)) {
            throw new Rejected("ASSERTION_BAD_AUD", "audience mismatch");
        }
        long now = Instant.now().getEpochSecond();
        long exp = p.path("exp").asLong(0);
        long iat = p.path("iat").asLong(0);
        if (now > exp + CLOCK_SKEW_SECONDS) throw new Rejected("ASSERTION_EXPIRED", "expired");
        if (iat > now + CLOCK_SKEW_SECONDS || exp - iat > TTL_SECONDS) {
            throw new Rejected("ASSERTION_INVALID", "lifetime out of bounds");
        }
        if (httpMethod != null && !httpMethod.equalsIgnoreCase(p.path("htm").asText())) {
            throw new Rejected("ASSERTION_BAD_HTM", "method binding");
        }
        if (requestPath != null && !requestPath.equals(p.path("htu").asText())) {
            throw new Rejected("ASSERTION_BAD_HTU", "path binding");
        }
        String jti = p.path("jti").asText();
        if (jti.isBlank()) throw new Rejected("ASSERTION_MISSING_JTI", "jti");
        consume(jti, exp, now);

        return new VerifiedActor(p.path("sub").asText(), p.path("tid").asText(),
            p.path("uname").asText(), p.path("clid").asText(), p.path("op").asText(),
            p.path("perm").asText(), p.path("target").asText(), p.path("corr").asText(), jti,
            aud.asText(), textOrNull(p, "act"), textOrNull(p, "home"));
    }

    private static String textOrNull(JsonNode p, String field) {
        String v = p.path(field).asText("");
        return v.isBlank() ? null : v;
    }

    private void consume(String jti, long exp, long now) {
        long[] slot = uses.computeIfAbsent(jti, k -> new long[]{0, exp});
        synchronized (slot) {
            if (slot[0] >= maxUses) {
                throw new Rejected(maxUses == 1 ? "ASSERTION_REPLAY" : "ASSERTION_OVER_BUDGET",
                    "assertion already used %d time(s)".formatted(slot[0]));
            }
            slot[0]++;
        }
        if (uses.size() > 10_000) uses.entrySet().removeIf(e -> e.getValue()[1] + CLOCK_SKEW_SECONDS < now);
    }

    // ---------------------------------------------------------------- helpers

    private static String nz(String s) { return s == null ? "" : s; }

    /** Public EC P-256 key → JWK members (kty, crv, x, y). */
    public static Map<String, Object> jwkOf(ECPublicKey key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kty", "EC");
        m.put("crv", "P-256");
        m.put("x", B64.encodeToString(fixed(key.getW().getAffineX().toByteArray())));
        m.put("y", B64.encodeToString(fixed(key.getW().getAffineY().toByteArray())));
        return m;
    }

    private static byte[] fixed(byte[] v) {
        byte[] out = new byte[32];
        int copy = Math.min(v.length, 32);
        System.arraycopy(v, v.length - copy, out, 32 - copy, copy);
        return out;
    }
}
