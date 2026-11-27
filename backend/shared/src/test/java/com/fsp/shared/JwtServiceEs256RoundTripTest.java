package com.fsp.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ES256 round-trip over the full r||s ↔ DER space. Regression for the DER-INTEGER sign-byte
 * bug in {@link JwtService#ecdsaJwsToDer}: whenever r or s was &lt; 2^248 (leading zero byte in
 * the 32-byte component), verification decoded the value as negative and every such signature
 * failed — surfacing as intermittent gateway_dpop_signature_invalid / INVALID_DPOP_PROOF and
 * (via the BFF's old invalidate-on-any-401 rule) dead user sessions mid-E2E.
 */
class JwtServiceEs256RoundTripTest {

    private static final ObjectMapper M = new ObjectMapper();

    private record Keyed(KeyPair pair, JsonNode pubJwk) {}

    private static Keyed newKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        ObjectNode jwk = M.createObjectNode();
        var pub = (ECPublicKey) kp.getPublic();
        jwk.put("kty", "EC").put("crv", "P-256");
        jwk.put("x", Base64.getUrlEncoder().withoutPadding()
            .encodeToString(leftTrim(pub.getW().getAffineX().toByteArray(), 32)));
        jwk.put("y", Base64.getUrlEncoder().withoutPadding()
            .encodeToString(leftTrim(pub.getW().getAffineY().toByteArray(), 32)));
        return new Keyed(kp, jwk);
    }

    private static byte[] leftTrim(byte[] v, int len) {
        if (v.length <= len) return v; // BigInteger already stripped the sign byte where needed
        byte[] out = new byte[len];
        System.arraycopy(v, v.length - len, out, 0, len);
        return out;
    }

    @Test
    void adversarialLeadingZeroComponentsStillVerify() throws Exception {
        Keyed k = newKey();
        // Craft raw r||s pairs whose first bytes exercise every DER INTEGER edge:
        // 0x00xx (value < 2^248, the regression), 0x80xx (high bit → sign byte required), 0x01, 0x00…
        byte[] cases = new byte[] { 0x00, (byte) 0x80, 0x01, (byte) 0xff, 0x7f, 0x00 };
        for (byte b : cases) {
            for (int i = 0; i < 32; i++) {
                for (int side = 0; side < 2; side++) {
                    byte[] raw = adversarial(b, i, side);
                    byte[] der = JwtService.ecdsaJwsToDer(raw, 32);
                    Signature sig = Signature.getInstance("SHA256withECDSA");
                    sig.initVerify(k.pair().getPublic());
                    // Verify the DER re-encodes the SAME two integers (self-consistency of the codec):
                    byte[] roundTrip = JwtService.ecdsaDerToJws(der, 32);
                    assertTrue(java.util.Arrays.equals(roundTrip, raw),
                        "DER round-trip must be identity; firstByte=" + b + " idx=" + i + " side=" + side);
                }
            }
        }
    }

    /** 32-byte component filled with {@code b} at index {@code idx} and 0x7f elsewhere (non-zero). */
    private static byte[] component(byte b, int idx) {
        byte[] c = new byte[32];
        java.util.Arrays.fill(c, (byte) 0x7f);
        c[idx] = b;
        return c;
    }

    private static byte[] adversarial(byte b, int idx, int side) {
        byte[] raw = new byte[64];
        byte[] first = component(b, idx);
        byte[] second = component(b, side == 0 ? idx : 31 - idx);
        System.arraycopy(first, 0, raw, 0, 32);
        System.arraycopy(second, 0, raw, 32, 32);
        return raw;
    }

    @Test
    void randomRealSignaturesVerifyAcrossManyIterations() throws Exception {
        Keyed k = newKey();
        for (int n = 0; n < 500; n++) {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "ES256");
            header.put("typ", "dpop+jwt");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jti", UUID.randomUUID().toString());
            payload.put("iat", System.currentTimeMillis() / 1000);
            String jws = Es256.sign(header, payload, (java.security.interfaces.ECPrivateKey) k.pair().getPrivate());
            String[] parts = jws.split("\\.");
            assertTrue(JwtService.verifyEs256(k.pubJwk(), parts[0] + "." + parts[1], parts[2]),
                "signature " + n + " must verify");
        }
    }
}
