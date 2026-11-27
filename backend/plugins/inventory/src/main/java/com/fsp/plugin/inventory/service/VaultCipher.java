package com.fsp.plugin.inventory.service;

import com.fsp.plugin.inventory.api.ApiProblemException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Voucher PIN vault (plans/inventory-plugin-plan.md D6): AES-256-GCM.
 *  - KEK = SHA-256(env FSP_PLUGIN_INVENTORY_KEK) — dev convenience; prod swaps this port for
 *    an HSM/KMS broker without touching callers (GAP-VAS-2 seam).
 *  - data key = HKDF-SHA256(KEK, salt=batch UUID) so every pool has its own key;
 *  - plaintext exists only at upload and at release; masked forms everywhere else.
 * Pins, keys and ciphertext are never logged (AGENTS §1.5).
 */
@Component
public class VaultCipher {

    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey kek;

    public VaultCipher(@Value("${fsp.plugin.kek:dev-inventory-kek-not-for-production}") String kekSecret) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(
                kekSecret.getBytes(StandardCharsets.UTF_8));
            this.kek = new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("vault KEK init failed", e);
        }
    }

    public record Sealed(byte[] ciphertext, byte[] nonce, byte[] tag) {}

    public Sealed seal(UUID batchId, String plaintext) {
        try {
            SecretKey dataKey = dataKey(batchId);
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] tag = new byte[GCM_TAG_BITS / 8];
            System.arraycopy(ct, ct.length - tag.length, tag, 0, tag.length);
            byte[] body = new byte[ct.length - tag.length];
            System.arraycopy(ct, 0, body, 0, body.length);
            return new Sealed(body, nonce, tag);
        } catch (Exception e) {
            // never leak the plaintext into logs
            throw new ApiProblemException("VAULT_SEAL_FAILED", "Voucher vault failure", 500);
        }
    }

    public String open(UUID batchId, byte[] ciphertext, byte[] nonce, byte[] tag) {
        try {
            SecretKey dataKey = dataKey(batchId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] joined = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, joined, 0, ciphertext.length);
            System.arraycopy(tag, 0, joined, ciphertext.length, tag.length);
            return new String(cipher.doFinal(joined), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiProblemException("VAULT_OPEN_FAILED", "Voucher vault failure", 500);
        }
    }

    /** Deterministic HKDF-SHA256(KEK, salt=batchId) — the pool's data key. */
    private SecretKey dataKey(UUID batchId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(kek);
        mac.update("fsp-inventory-voucher-v1".getBytes(StandardCharsets.UTF_8));
        byte[] salt = batchId.toString().getBytes(StandardCharsets.UTF_8);
        mac.update(salt);
        return new SecretKeySpec(mac.doFinal(), "AES");
    }

    /** Masked display form: first 4 + last 4 of the code, middle replaced (AGENTS §1.5). */
    public static String mask(String pin) {
        if (pin == null || pin.length() <= 8) return "****";
        return pin.substring(0, 4) + "****" + pin.substring(pin.length() - 4);
    }
}
