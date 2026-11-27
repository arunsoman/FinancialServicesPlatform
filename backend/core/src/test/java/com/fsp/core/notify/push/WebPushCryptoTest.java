package com.fsp.core.notify.push;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 8291/8292 crypto unit tests — JDK roundtrip: encrypt with an application-server key,
 * decrypt with the same keys the browser side would hold (user agent generates its own
 * P-256 pair and its auth secret). Verifies header layout, HKDF chain, GCM record and the
 * public-point re-derivation from the private key.
 */
class WebPushCryptoTest {

    @Test
    void encryptDecryptRoundtrip() throws Exception {
        KeyPair uaPair = p256();
        byte[] uaPublic = point(uaPair);
        byte[] auth = random(16);
        byte[] plaintext = "{\"type\":\"APPROVAL_OPEN\",\"title\":\"Awaiting approval\",\"body\":\"A settlement bank account filing is waiting\"}"
            .getBytes(StandardCharsets.UTF_8);

        byte[] record = WebPushCrypto.encrypt(plaintext, uaPublic, asPrivate(), auth);
        // the user-agent key pair and auth secret are what a real browser holds; the same
        // auth secret was passed to encrypt() — the decrypt oracle mirrors RFC 8291 §5.
        byte[] decrypted = decrypt(record, uaPair.getPrivate(), auth);

        assertTrue(Arrays.equals(plaintext, decrypted), "roundtrip must restore plaintext");
    }

    @Test
    void recordHeaderLayout() throws Exception {
        byte[] record = WebPushCrypto.encrypt("x".getBytes(StandardCharsets.UTF_8), point(p256()), asPrivate(), random(16));
        // RFC 8188 §2: salt(16) ‖ rs(4) ‖ idlen(1) ‖ key
        int rs = ((record[16] & 0xFF) << 24) | ((record[17] & 0xFF) << 16) | ((record[18] & 0xFF) << 8) | (record[19] & 0xFF);
        assertEquals(4096, rs, "record size field");
        assertEquals(65, record[20] & 0xFF, "key id length = uncompressed P-256 point");
        assertEquals(0x04, record[21], "key id = uncompressed point tag");
        assertEquals(16 + 4 + 1 + 65 + 18, record.length,
            "header(86) + ciphertext(len+1 + GCM tag16) for a 1-byte record");
    }

    @Test
    void sameInputDifferentCiphertext() throws Exception {
        KeyPair ua = p256();
        byte[] plaintext = "deterministic-input".getBytes(StandardCharsets.UTF_8);
        byte[] one = WebPushCrypto.encrypt(plaintext, point(ua), asPrivate(), random(16));
        byte[] two = WebPushCrypto.encrypt(plaintext, point(ua), asPrivate(), random(16));
        assertNotEquals(Arrays.toString(one), Arrays.toString(two), "fresh salt per encryption");
    }

    @Test
    void derivedPublicPointMatchesJca() throws Exception {
        KeyPair kp = p256();
        byte[] derived = WebPushCrypto.uncompressedPointFromPrivate(kp.getPrivate());
        byte[] jca = ((ECPublicKey) kp.getPublic()).getW() == null ? null : point(kp);
        assertNotEquals(0, derived.length);
        assertTrue(Arrays.equals(jca, derived), "scalar double-and-add must reproduce the JCA public point");
    }

    @Test
    void vapidJwtSignsAndVerifies() throws Exception {
        KeyPair pair = p256(); // one pair signed AND verified — asKey() is generator-fresh per call
        String jwt = WebPushCrypto.vapidJwt(pair.getPrivate(), "mailto:dev@fsp.local", "https://fcm.googleapis.com");
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        // verify against the AS public key (same pair → public derived from the private)
        byte[] pub = WebPushCrypto.uncompressedPointFromPrivate(pair.getPrivate());
        KeyFactory kf = KeyFactory.getInstance("EC");
        PublicKey verifyKey = kf.generatePublic(
            new java.security.spec.ECPublicKeySpec(WebPushCrypto.decodePoint(pub), WebPushCrypto.P256_SPEC));
        Signature sig = Signature.getInstance("SHA256withECDSAINP1363FORMAT");
        sig.initVerify(verifyKey);
        sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(sig.verify(Base64.getUrlDecoder().decode(parts[2])), "ES256 (r||s) signature must verify");
        assertTrue(new String(Base64.getUrlDecoder().decode(parts[1])).contains("\"sub\":\"mailto:dev@fsp.local\""));
    }

    // ---- helpers ----

    /** The application-server key: a real P-256 private key, PKCS#8 (what config holds). */
    static PrivateKey asKey() throws Exception {
        return p256().getPrivate();
    }

    static byte[] asPrivate() throws Exception {
        return asKey().getEncoded();
    }

    static KeyPair p256() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static byte[] point(KeyPair kp) {
        return WebPushCrypto.uncompressedPoint((ECPublicKey) kp.getPublic());
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    /** Decrypt side: what a browser push service receives re-encrypted (test oracle). */
    static byte[] decrypt(byte[] record, PrivateKey uaPrivate, byte[] auth) throws Exception {
        int idlen = record[20] & 0xFF;
        byte[] salt = Arrays.copyOfRange(record, 0, 16);
        byte[] asPublicBytes = Arrays.copyOfRange(record, 21, 21 + idlen);
        byte[] ciphertext = Arrays.copyOfRange(record, 21 + idlen, record.length);

        ECPublicKey asPublic = (ECPublicKey) KeyFactory.getInstance("EC")
            .generatePublic(new java.security.spec.ECPublicKeySpec(
                WebPushCrypto.decodePoint(asPublicBytes), WebPushCrypto.P256_SPEC));
        byte[] uaPublicBytes = WebPushCrypto.uncompressedPointFromPrivate(uaPrivate);

        javax.crypto.KeyAgreement ka = javax.crypto.KeyAgreement.getInstance("ECDH");
        ka.init(uaPrivate);
        ka.doPhase(asPublic, true);
        byte[] ecdhSecret = ka.generateSecret();

        byte[] ikmInfo = new byte[14 + uaPublicBytes.length + asPublicBytes.length];
        System.arraycopy("WebPush: info\0".getBytes(StandardCharsets.UTF_8), 0, ikmInfo, 0, 14);
        System.arraycopy(uaPublicBytes, 0, ikmInfo, 14, uaPublicBytes.length);
        System.arraycopy(asPublicBytes, 0, ikmInfo, 14 + uaPublicBytes.length, asPublicBytes.length);

        byte[] ikm = WebPushCrypto.hkdf(auth, ecdhSecret, ikmInfo, 32);
        byte[] prkKey = WebPushCrypto.hkdf(ikm, auth,
            "Content-Encoding: auth\0".getBytes(StandardCharsets.UTF_8), 32);
        byte[] cek = WebPushCrypto.hkdf(prkKey, salt,
            "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.UTF_8), 16);
        byte[] nonce = WebPushCrypto.hkdf(prkKey, salt,
            "Content-Encoding: nonce\0".getBytes(StandardCharsets.UTF_8), 12);

        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE,
            new javax.crypto.spec.SecretKeySpec(cek, "AES"),
            new javax.crypto.spec.GCMParameterSpec(128, nonce));
        byte[] plaintext = c.doFinal(ciphertext);
        // strip padding: everything after the last 0x02 delimiter byte
        int cut = -1;
        for (int i = plaintext.length - 1; i >= 0; i--) {
            if (plaintext[i] == 0x02) { cut = i; break; }
        }
        return Arrays.copyOfRange(plaintext, 0, cut);
    }
}
