package com.fsp.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal RS256 JWT engine (sign/verify/JWKS) shared by the FSP IdP (signs), the API gateway
 * (verifies) and core (future introspection checks). Zero third-party crypto deps: JCA only.
 * Built to be replaced-by-interface, never extended ad hoc — token shaping follows the
 * FSP implementation guide (sender constraints arrive with the FAPI2 slice).
 */
public final class JwtService {

    public static final String DEFAULT_AUDIENCE = "fsp-financial-api";
    public static final String KID = "fsp-idp-sign-1";

    /** Canonical principal claims minted by the IdP; identity re-issue at the gateway maps them 1:1. */
    public record Claims(String userId, String tenantId, String username, String clientId,
                         List<String> roles, Instant issuedAt, Instant expiresAt, String audience) {}

    private final RSAPrivateCrtKey privateKey;
    private final String issuer;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JwtService(Path signingKeyPem, String issuer) {
        this.issuer = issuer;
        RSAPrivateCrtKey key = null;
        if (signingKeyPem != null && Files.exists(signingKeyPem)) {
            try {
                key = readPkcs8(signingKeyPem);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("idp signing key unreadable: " + signingKeyPem, e);
            }
        }
        this.privateKey = key;
    }

    public boolean canSign() {
        return privateKey != null;
    }

    public String issuer() {
        return issuer;
    }

    // ---------------- minting (IdP) ----------------

    public String sign(Claims claims, String clientId, String audience) {
        return signWithCnf(claims, clientId, audience, null);
    }

    /**
     * Sign JWT with optional cnf.jkt claim for DPoP token binding (RFC 9449, FAPI 2.0).
     * @param jkt JWK thumbprint (base64url-encoded SHA-256 of canonical JWK) or null
     */
    public String signWithCnf(Claims claims, String clientId, String audience, String jkt) {
        if (privateKey == null) throw new IllegalStateException("idp has no signing key");
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", KID);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("aud", audience == null ? DEFAULT_AUDIENCE : audience);
        payload.put("sub", claims.userId());
        payload.put("tid", claims.tenantId());
        payload.put("uname", claims.username());
        payload.put("clid", clientId);
        payload.put("roles", claims.roles());
        payload.put("iat", now);
        payload.put("nbf", now - 5);
        payload.put("exp", claims.expiresAt().getEpochSecond());
        payload.put("jti", UUID.randomUUID().toString());
        if (jkt != null && !jkt.isBlank()) {
            Map<String, String> cnf = new LinkedHashMap<>();
            cnf.put("jkt", jkt);
            payload.put("cnf", cnf);
        }
        return doSign(header, payload);
    }

    private String doSign(Map<String, Object> header, Map<String, Object> payload) {
        try {
            String h = b64u(MAPPER.writeValueAsBytes(header));
            String p = b64u(MAPPER.writeValueAsBytes(payload));
            String signingInput = h + "." + p;
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String s = b64u(sig.sign());
            return signingInput + "." + s;
        } catch (Exception e) {
            throw new IllegalStateException("jwt signing failed", e);
        }
    }

    // ---------------- ES256 / JWK helpers (DPoP, private_key_jwt — FAPI 2.0) ----------------

    /** P-256 EC public key from a JWK (kty EC, crv P-256, x/y base64url). */
    public static java.security.interfaces.ECPublicKey ecPublicKeyFromJwk(JsonNode jwk) {
        try {
            if (!"EC".equals(jwk.path("kty").asText()) || !"P-256".equals(jwk.path("crv").asText())) {
                throw new IllegalArgumentException("unsupported key type/curve");
            }
            BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("x").asText()));
            BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("y").asText()));
            java.security.spec.ECParameterSpec spec = p256Spec();
            return (java.security.interfaces.ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new java.security.spec.ECPublicKeySpec(new java.security.spec.ECPoint(x, y), spec));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("invalid ec jwk", e);
        }
    }

    /** Canonical P-256 parameters via AlgorithmParameters (no hardcoded hex, provider-backed). */
    public static java.security.spec.ECParameterSpec p256Spec() {
        try {
            java.security.AlgorithmParameters ap = java.security.AlgorithmParameters.getInstance("EC");
            ap.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
            return ap.getParameterSpec(java.security.spec.ECParameterSpec.class);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("secp256r1 parameters unavailable", e);
        }
    }

    /** ES256 (EC P-256) verification — input signature is JWS raw r||s; converted to DER for JCA. */
    public static boolean verifyEs256(JsonNode jwk, String signingInput, String signatureB64url) {
        try {
            var pub = ecPublicKeyFromJwk(jwk);
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(pub);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return sig.verify(ecdsaJwsToDer(Base64.getUrlDecoder().decode(signatureB64url), 32));
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** Convert a JWS raw r||s signature (componentLen bytes each) into JCA DER form. */
    public static byte[] ecdsaJwsToDer(byte[] raw, int componentLen) {
        if (raw.length != componentLen * 2) {
            throw new IllegalArgumentException("raw signature length mismatch");
        }
        byte[] r = derIntegerContent(raw, 0, componentLen);
        byte[] s = derIntegerContent(raw, componentLen, componentLen);
        byte[] der = new byte[6 + r.length + s.length];
        int i = 0;
        der[i++] = 0x30; der[i++] = (byte) (der.length - 2);
        der[i++] = 0x02; der[i++] = (byte) r.length;
        System.arraycopy(r, 0, der, i, r.length); i += r.length;
        der[i++] = 0x02; der[i++] = (byte) s.length;
        System.arraycopy(s, 0, der, i, s.length);
        return der;
    }

    /**
     * DER INTEGER content for a non-negative big-endian value: strip redundant leading
     * zeros, then RE-ADD a 0x00 sign byte when the first remaining byte has its high bit
     * set — without it the value decodes as NEGATIVE and ES256 verification fails (~0.8%
     * of random r/s values, seen as intermittent gateway_dpop_signature_invalid).
     */
    private static byte[] derIntegerContent(byte[] raw, int off, int len) {
        while (len > 0 && raw[off] == 0) { off++; len--; }
        if (len == 0) return new byte[] { 0 }; // value 0 → single zero byte
        boolean needsSign = (raw[off] & 0x80) != 0;
        byte[] out = new byte[len + (needsSign ? 1 : 0)];
        System.arraycopy(raw, off, out, out.length - len, len);
        return out;
    }

    /** Convert a JCA DER ECDSA signature into JWS raw form (componentLen bytes each). */
    public static byte[] ecdsaDerToJws(byte[] der, int componentLen) {
        if (der[0] != 0x30) throw new IllegalArgumentException("not a DER sequence");
        int i = 2;
        if (der[i++] != 0x02) throw new IllegalArgumentException("no r INTEGER");
        int rLen = der[i++] & 0xff;
        byte[] r = positive(der, i, rLen, componentLen);
        i += rLen;
        if (der[i++] != 0x02) throw new IllegalArgumentException("no s INTEGER");
        int sLen = der[i++] & 0xff;
        byte[] s = positive(der, i, sLen, componentLen);
        byte[] raw = new byte[componentLen * 2];
        System.arraycopy(r, 0, raw, 0, componentLen);
        System.arraycopy(s, 0, raw, componentLen, componentLen);
        return raw;
    }

    /** Copy src[off..off+len) into a componentLen array with leading-zero strip (DER INTEGER normalization). */
    private static byte[] positive(byte[] src, int off, int len, int componentLen) {
        while (src[off] == 0 && len > 1) { off++; len--; } // strip leading sign zeros
        byte[] out = new byte[componentLen];
        int copy = Math.min(len, componentLen);
        System.arraycopy(src, off, out, componentLen - copy, copy);
        return out;
    }

    /** RFC 7638 JWK thumbprint (EC: crv/kty/x/y; RSA: e/kty/n), b64url(SHA-256). */
    public static String jwkThumbprint(JsonNode jwk) {
        try {
            String cano;
            if ("EC".equals(jwk.path("kty").asText())) {
                cano = "{\"crv\":\"" + jwk.path("crv").asText() + "\",\"kty\":\"EC\",\"x\":\""
                    + jwk.path("x").asText() + "\",\"y\":\"" + jwk.path("y").asText() + "\"}";
            } else if ("RSA".equals(jwk.path("kty").asText())) {
                cano = "{\"e\":\"" + jwk.path("e").asText() + "\",\"kty\":\"RSA\",\"n\":\""
                    + jwk.path("n").asText() + "\"}";
            } else {
                throw new IllegalArgumentException("unsupported kty");
            }
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(cano.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    // ---------------- verification (gateway / core) ----------------

    /** Verify against the published JWKS (gateway fetched from IdP). Enforces iss/aud/exp/nbf. */
    public Claims verify(String token, Map<String, JsonNode> jwksByKeyId, String expectedIssuer,
                         String expectedAudience) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        try {
            JsonNode headerNode = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"RS256".equals(headerNode.path("alg").asText())) return null;
            String kid = headerNode.path("kid").asText(KID);
            JsonNode jwk = jwksByKeyId.get(kid);
            if (jwk == null || !"RSA".equals(jwk.path("kty").asText())) return null;
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKeyFrom(jwk.path("n").asText(), jwk.path("e").asText()));
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) return null;

            JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long now = Instant.now().getEpochSecond();
            if (now > payload.path("exp").asLong()) return null;
            if (now < payload.path("nbf").asLong(0)) return null;
            if (expectedIssuer != null && !expectedIssuer.equals(payload.path("iss").asText())) return null;
            if (expectedAudience != null && !expectedAudience.equals(payload.path("aud").asText())) return null;
            return toClaims(payload);
        } catch (Exception e) {
            return null;
        }
    }

    public static Claims toClaims(JsonNode payload) {
        return new Claims(
            payload.path("sub").asText(),
            payload.path("tid").asText(),
            payload.path("uname").asText(),
            payload.path("clid").asText(),
            payload.path("roles").isMissingNode() ? List.of() : mapToStringList(payload.path("roles")),
            Instant.ofEpochSecond(payload.path("iat").asLong()),
            Instant.ofEpochSecond(payload.path("exp").asLong()),
            payload.path("aud").asText());
    }

    private static List<String> mapToStringList(JsonNode array) {
        return mapToStringListStatic(array);
    }

    private static List<String> mapToStringListStatic(JsonNode array) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }

    // ---------------- JWKS publication ----------------

    public String jwksJson() {
        if (privateKey == null) throw new IllegalStateException("idp has no signing key");
        RSAPublicKey pub = (RSAPublicKey) publicKeyFrom(privateKey);
        try {
            return MAPPER.writeValueAsString(Map.of("keys", List.of(Map.of(
                "kty", "RSA", "use", "sig", "alg", "RS256", "kid", KID,
                "n", b64u(toBytesUnsigned(pub.getModulus())),
                "e", b64u(toBytesUnsigned(pub.getPublicExponent()))))));
        } catch (Exception e) {
            throw new IllegalStateException("jwks serialization failed", e);
        }
    }

    // ---------------- key loading ----------------

    public static RSAPrivateCrtKey readPkcs8(Path pem) throws IOException {
        String pemText = Files.readString(pem)
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getMimeDecoder().decode(pemText);
        try {
            return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("not a PKCS#8 RSA key: " + pem, e);
        }
    }

    public static PublicKey publicKeyFrom(RSAPrivateCrtKey key) {
        try {
            return KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        } catch (Exception e) {
            throw new IllegalStateException("public key derivation failed", e);
        }
    }

    public static PublicKey publicKeyFrom(String nB64u, String eB64u) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, Base64.getUrlDecoder().decode(nB64u)),
                new BigInteger(1, Base64.getUrlDecoder().decode(eB64u))));
        } catch (Exception e) {
            throw new IllegalStateException("jwk reconstruction failed", e);
        }
    }

    /** Parse JWKS JSON into keyed nodes (gateway-side convenience). */
    public static Map<String, JsonNode> jwksMap(String jwksJson) {
        try {
            Map<String, JsonNode> out = new HashMap<>();
            JsonNode root = MAPPER.readTree(jwksJson);
            root.path("keys").forEach(k -> out.put(k.path("kid").asText("fsp-idp-sign-1"), k));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("jwks parse failed", e);
        }
    }

    private static byte[] toBytesUnsigned(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] out = new byte[raw.length - 1];
            System.arraycopy(raw, 1, out, 0, out.length);
            return out;
        }
        return raw;
    }

    private static String b64u(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}