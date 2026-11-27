package com.fsp.core.notify.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * RFC 8291 (aes128gcm) message encryption + RFC 8292 (VAPID) for Web Push — JDK-only
 * (ECDH P-256 key agreement, HKDF over HmacSHA256, AES-GCM, "SHA256withECDSAINP1363FORMAT"
 * = JWS raw r||s). Keys are base64url, no PEM: the application server key pair is the VAPID
 * pair itself ({@code fsp.push.webpush.public-key/private-key}).
 */
final class WebPushCrypto {

    private WebPushCrypto() {}

    private static final byte[] WEBPUSH_INFO_PREFIX = "WebPush: info\0".getBytes();
    private static final byte[] CONTENT_AUTH_INFO = "Content-Encoding: auth\0".getBytes();
    private static final byte[] CONTENT_CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes();
    private static final byte[] CONTENT_NONCE_INFO = "Content-Encoding: nonce\0".getBytes();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Generate a VAPID P-256 key pair, base64url: [0]=public (65-byte uncompressed point), [1]=private (PKCS#8). */
    static String[] generateVapidKeys() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(com.fsp.core.notify.push.WebPushCrypto.P256_SPEC);
            KeyPair kp = g.generateKeyPair();
            return new String[]{
                Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressedPoint((ECPublicKey) kp.getPublic())),
                Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPrivate().getEncoded())};
        } catch (Exception e) {
            throw new IllegalStateException("vapid keygen failed", e);
        }
    }

    static final java.security.spec.ECParameterSpec P256_SPEC = com.fsp.shared.JwtService.p256Spec();

    /** RFC 8292 VAPID JWT (ES256): aud=push-service origin, exp=+12h, sub=mailto. */
    static String vapidJwt(PrivateKey asKey, String subject, String audience) {
        try {
            long exp = java.time.Instant.now().plus(java.time.Duration.ofHours(12)).getEpochSecond();
            String header = b64uJson("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
            String payload = b64uJson("{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"%s\"}".formatted(audience, exp, subject));
            String signingInput = header + "." + payload;
            Signature sig = Signature.getInstance("SHA256withECDSAINP1363FORMAT");
            sig.initSign(asKey);
            sig.update(signingInput.getBytes());
            String jws = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
            return signingInput + "." + jws;
        } catch (Exception e) {
            throw new IllegalStateException("vapid jwt failed", e);
        }
    }

    /** RFC 8291 encrypt: plaintext → aes128gcm record (header ‖ ciphertext). */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublicPoint, byte[] asPrivatePkcs8, byte[] authSecret) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            if (plaintext.length > 4096 - 2) throw new IllegalArgumentException("payload too large for one record");

            // ecdh_secret = ECDH(as_private, ua_public)
            KeyFactory kf = KeyFactory.getInstance("EC");
            ECPoint uaPoint = decodePoint(uaPublicPoint);
            ECPublicKey uaPublic = (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(uaPoint, P256_SPEC));
            PrivateKey asPrivate = kf.generatePrivate(new PKCS8EncodedKeySpec(asPrivatePkcs8));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(asPrivate);
            ka.doPhase(uaPublic, true);
            byte[] ecdhSecret = ka.generateSecret();

            byte[] asPublicPoint = uncompressedPointFromPrivate(asPrivate);
            // IKM = HKDF(salt=auth_secret, ikm=ecdh_secret,
            //            info="WebPush: info" NUL ua_public ‖ as_public, L=32)
            byte[] ikmInfo = new byte[WEBPUSH_INFO_PREFIX.length + uaPublicPoint.length + asPublicPoint.length];
            System.arraycopy(WEBPUSH_INFO_PREFIX, 0, ikmInfo, 0, WEBPUSH_INFO_PREFIX.length);
            System.arraycopy(uaPublicPoint, 0, ikmInfo, WEBPUSH_INFO_PREFIX.length, uaPublicPoint.length);
            System.arraycopy(asPublicPoint, 0, ikmInfo, WEBPUSH_INFO_PREFIX.length + uaPublicPoint.length, asPublicPoint.length);
            byte[] ikm = hkdf(authSecret, ecdhSecret, ikmInfo, 32);
            // PRK_key = HKDF(salt=IKM, ikm=auth_secret, info="Content-Encoding: auth"NUL, L=32)
            byte[] prkKey = hkdf(ikm, authSecret, CONTENT_AUTH_INFO, 32);

            // CEK / nonce from PRK_key over the fresh salt
            byte[] cek = hkdf(prkKey, salt, CONTENT_CEK_INFO, 16);
            byte[] nonce = hkdf(prkKey, salt, CONTENT_NONCE_INFO, 12);

            // One record: plaintext ‖ 0x02 (padding delimiter, zero pad), 32-bit record size header
            byte[] record = new byte[plaintext.length + 1];
            System.arraycopy(plaintext, 0, record, 0, plaintext.length);
            record[plaintext.length] = 0x02;

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = c.doFinal(record);

            // aes128gcm header (RFC 8188 §2): salt(16) ‖ rs(4) ‖ idlen(1) ‖ key id
            byte[] header = new byte[16 + 4 + 1];
            System.arraycopy(salt, 0, header, 0, 16);
            header[16] = (byte) ((4096 >>> 24) & 0xFF);
            header[17] = (byte) ((4096 >>> 16) & 0xFF);
            header[18] = (byte) ((4096 >>> 8) & 0xFF);
            header[19] = (byte) (4096 & 0xFF);
            header[20] = (byte) asPublicPoint.length;
            byte[] out = new byte[header.length + asPublicPoint.length + ciphertext.length];
            System.arraycopy(header, 0, out, 0, header.length);
            System.arraycopy(asPublicPoint, 0, out, header.length, asPublicPoint.length);
            System.arraycopy(ciphertext, 0, out, header.length + asPublicPoint.length, ciphertext.length);
            return out;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("webpush encrypt failed", e);
        }
    }

    /** HKDF-Extract+Expand (RFC 5869) over HmacSHA256. */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            byte[] out = new byte[length];
            byte[] t = new byte[0];
            int offset = 0;
            for (int i = 1; offset < length; i++) {
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                System.arraycopy(t, 0, out, offset, Math.min(t.length, length - offset));
                offset += t.length;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("hkdf failed", e);
        }
    }

    static ECPoint decodePoint(byte[] p) {
        if (p.length != 65 || p[0] != 0x04) throw new IllegalArgumentException("expected uncompressed P-256 point");
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(p, 1, 33));
        BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(p, 33, 65));
        return new ECPoint(x, y);
    }

    static byte[] uncompressedPoint(ECPublicKey k) {
        return uncompressedPoint(k.getW());
    }

    static byte[] uncompressedPointFromPrivate(PrivateKey k) throws Exception {
        BigInteger d = ((java.security.interfaces.ECPrivateKey) k).getS();
        ECPoint q = multiplyG(P256_SPEC.getGenerator(), d);
        return uncompressedPoint(q);
    }

    private static byte[] uncompressedPoint(ECPoint w) {
        byte[] x = fixed32(w.getAffineX());
        byte[] y = fixed32(w.getAffineY());
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    private static byte[] fixed32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        int src = Math.max(0, b.length - 32);
        System.arraycopy(b, src, out, 32 - (b.length - src), b.length - src);
        return out;
    }

    /** Scalar multiply on secp256r1 (only used to re-derive our own public point). */
    static ECPoint multiplyG(java.security.spec.ECPoint g, BigInteger d) {
        java.security.spec.EllipticCurve curve = P256_SPEC.getCurve();
        BigInteger a = curve.getA();
        BigInteger p = ((java.security.spec.ECFieldFp) curve.getField()).getP();
        ECPoint r = ECPoint.POINT_INFINITY;
        ECPoint q = g;
        BigInteger k = d;
        while (k.signum() > 0) {
            if (k.testBit(0)) r = add(r, q, a, p);
            q = add(q, q, a, p);
            k = k.shiftRight(1);
        }
        if (r.equals(ECPoint.POINT_INFINITY)) throw new IllegalStateException("point at infinity");
        return new ECPoint(r.getAffineX().mod(p), r.getAffineY().mod(p));
    }

    private static ECPoint add(ECPoint a, ECPoint b, BigInteger curveA, BigInteger p) {
        if (a.equals(ECPoint.POINT_INFINITY)) return b;
        if (b.equals(ECPoint.POINT_INFINITY)) return a;
        BigInteger lambda;
        if (a.getAffineX().equals(b.getAffineX())) {
            if (!a.getAffineY().equals(b.getAffineY())) return ECPoint.POINT_INFINITY;
            lambda = a.getAffineX().pow(2).multiply(BigInteger.valueOf(3)).add(curveA)
                .multiply(a.getAffineY().multiply(BigInteger.TWO).mod(p).modInverse(p)).mod(p);
        } else {
            lambda = b.getAffineY().subtract(a.getAffineY())
                .multiply(b.getAffineX().subtract(a.getAffineX()).mod(p).modInverse(p)).mod(p);
        }
        BigInteger x = lambda.pow(2).subtract(a.getAffineX()).subtract(b.getAffineX()).mod(p);
        BigInteger y = lambda.multiply(a.getAffineX().subtract(x)).subtract(a.getAffineY()).mod(p);
        return new ECPoint(x, y);
    }

    private static String b64uJson(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes());
    }
}
