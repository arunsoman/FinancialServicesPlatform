package com.fsp.idp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Account activation (V36) — how an onboarded person (org applicant or KYC customer) gets
 * their FIRST password. Nobody else ever knows it: the person is born with a random secret,
 * and the IdP sends a one-time proof to the PRIMARY contact:
 * <ul>
 *   <li><b>EMAIL</b> — a 256-bit single-use link token (hashed at rest, 72 h);</li>
 *   <li><b>PHONE</b> — an 8-digit one-time code by SMS (hashed and bound to the person, 15 min,
 *       ≤5 attempts). No secret-bearing link ever goes over SMS (§12.4); the message ends with
 *       the origin-bound one-time-code line ({@code @host #code}) so the browser autofills it
 *       only on the real origin — the SMS-specific phishing defence.</li>
 * </ul>
 * Completing it — at the IdP's own hosted page, never the SPA — sets the password and stamps
 * {@code contact_verified_at}. A new send supersedes any earlier one. Sign-in stays blocked
 * until then ({@link IdpTokenService}); customers additionally stay PENDING until a checker
 * activates them in core.
 */
@Service
public class IdpActivationService {

    static final String PURPOSE = "ACTIVATION";
    static final long EMAIL_TTL_HOURS = 72;
    static final long SMS_TTL_MINUTES = 15;
    static final int MAX_ATTEMPTS = 5;
    static final int CODE_DIGITS = 8;

    private static final Logger log = LoggerFactory.getLogger(IdpActivationService.class);

    private final IdpAudit audit;
    private final PPersonRepository persons;
    private final PasswordResetRepository resets;
    private final IdpAuthService auth;
    private final IdpProperties props;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public IdpActivationService(PPersonRepository persons, PasswordResetRepository resets,
                                IdpAuthService auth, IdpProperties props, IdpAudit audit) {
        this.audit = audit;
        this.persons = persons;
        this.resets = resets;
        this.auth = auth;
        this.props = props;
    }

    /** What the person proves: the emailed link token, or their phone + the SMS code. */
    public sealed interface Proof permits Proof.Link, Proof.Code {
        record Link(String token) implements Proof {}
        record Code(String phone, String code) implements Proof {}
    }

    /** Send (or re-send) the activation. Silent no-op when there is nothing to activate. */
    @Transactional
    public void send(UUID personId) {
        PPerson p = persons.findById(personId).orElse(null);
        if (p == null || p.primaryContact == null || p.contactVerifiedAt != null
            || "ARCHIVED".equals(p.status) || "DISABLED".equals(p.status)) {
            log.info("idp_activation_send outcome=nothing_to_activate person={}", personId);
            return;
        }
        Instant now = Instant.now();
        resets.consumePending(p.id, now); // supersede: only the newest link/code works
        PasswordReset row = new PasswordReset();
        row.id = UUID.randomUUID();
        row.personId = p.id;
        row.purpose = PURPOSE;
        if ("PHONE".equals(p.primaryContact)) {
            if (p.phone == null || p.phone.isBlank()) {
                audit.record(null, p.tenantId, "ACTIVATION_UNREACHABLE", p.id, "channel=SMS;reason=no_phone");
                log.warn("idp_activation_unreachable person={} reason=no_phone", p.id);
                return;
            }
            String code = newCode();
            row.channel = "SMS";
            row.tokenHash = codeHash(p.id, code);
            row.expiresAt = now.plusSeconds(SMS_TTL_MINUTES * 60);
            save(row);
            String host = host();
            auth.dispatch(p.id, PURPOSE, "SMS", "FSP activation code",
                "FSP: " + code + " is your activation code for " + p.username + ". It expires in "
                    + SMS_TTL_MINUTES + " min. Enter it at " + host + "/idp/activate to set your password. "
                    + "FSP never asks for this code by phone.\n\n@" + host + " #" + code);
        } else {
            if (p.email == null || !IdpAuthService.plausibleEmail(p.email)) {
                audit.record(null, p.tenantId, "ACTIVATION_UNREACHABLE", p.id, "channel=EMAIL;reason=no_usable_email");
                log.warn("idp_activation_unreachable person={} reason=no_usable_email", p.id);
                return;
            }
            String token = newToken();
            row.channel = "EMAIL";
            row.tokenHash = IdpAuthService.sha256Hex(token);
            row.expiresAt = now.plusSeconds(EMAIL_TTL_HOURS * 3600);
            save(row);
            auth.dispatch(p.id, PURPOSE, "EMAIL", "Activate your FSP account",
                "An FSP account was opened for you. Your sign-in name is: " + p.username + "\n\n"
                    + "To activate it, choose your own password with this one-time link (valid "
                    + EMAIL_TTL_HOURS + " hours, works once):\n\n"
                    + props.getPublicBaseUrl() + "/idp/activate?token=" + token + "\n\n"
                    + "Nobody at FSP knows or will ever ask for your password. If you did not expect "
                    + "this account, ignore this mail.");
        }
        audit.record(null, p.tenantId, "ACTIVATION_ISSUED", p.id,
            "channel=" + row.channel + ";activationId=" + row.id + ";expiresAt=" + row.expiresAt);
        log.info("idp_activation_sent person={} channel={} customer={}", p.id, row.channel, p.isCustomer);
    }

    /** The hosted page shows the password form only for a live link (it does not consume it). */
    @Transactional(readOnly = true)
    public boolean linkIsLive(String token) {
        return liveByToken(token).isPresent();
    }

    /**
     * Complete activation: proof valid → own password set, contact verified, proof consumed.
     * Returns the sign-in name to show on the success page. Policy/mismatch failures do NOT
     * consume the proof (the person retries); wrong codes count against the attempt cap.
     */
    @Transactional(noRollbackFor = IdpProblemException.class)
    public Completed complete(Proof proof, String newPassword, String confirmPassword) {
        PasswordReset row = switch (proof) {
            case Proof.Link l -> liveByToken(l.token()).orElse(null);
            case Proof.Code c -> liveByCode(c.phone(), c.code());
        };
        if (row == null) {
            throw IdpAuthService.problem("ACTIVATION_INVALID",
                "This activation is invalid or has expired. Ask for a new one.", 400);
        }
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            throw IdpAuthService.problem("ACTIVATION_CONFIRM_MISMATCH", "The two passwords do not match.", 400);
        }
        IdpAuthService.passwordPolicy(newPassword);
        PPerson p = persons.findById(row.personId).orElse(null);
        if (p == null || p.contactVerifiedAt != null || "ARCHIVED".equals(p.status) || "DISABLED".equals(p.status)) {
            throw IdpAuthService.problem("ACTIVATION_INVALID",
                "This activation is invalid or has expired. Ask for a new one.", 400);
        }
        Instant now = Instant.now();
        p.passwordHash = bcrypt.encode(newPassword);
        p.mustChangePassword = false;
        p.contactVerifiedAt = now;
        persons.save(p); // @Version CAS against concurrent core writes
        row.consumedAt = now;
        resets.save(row);
        audit.record(p.id, p.tenantId, "ACTIVATION_COMPLETED", p.id,
            "channel=" + row.channel + ";activationId=" + row.id + ";contactVerifiedAt=" + now);
        log.info("idp_activation_completed person={} channel={} customer={} status={}",
            p.id, row.channel, p.isCustomer, p.status);
        return new Completed(p.username, "ACTIVE".equals(p.status));
    }

    /** signInNow: false for a customer still waiting for the checker. */
    public record Completed(String username, boolean signInNow) {}

    private Optional<PasswordReset> liveByToken(String token) {
        if (token == null || token.isBlank() || token.length() > 128) return Optional.empty();
        return resets.findFirstByTokenHashAndConsumedAtIsNullOrderByCreatedAtDesc(IdpAuthService.sha256Hex(token.trim()))
            .filter(r -> PURPOSE.equals(r.purpose) && "EMAIL".equals(r.channel))
            .filter(r -> r.expiresAt.isAfter(Instant.now()));
    }

    /** Phone + code: the code hash is bound to the person, so a code is useless for anyone else. */
    private PasswordReset liveByCode(String rawPhone, String rawCode) {
        String phone = e164(rawPhone);
        String code = rawCode == null ? "" : rawCode.replaceAll("\\s", "");
        if (phone == null || !code.matches("\\d{" + CODE_DIGITS + "}")) return null;
        List<PPerson> candidates = persons.findByPhoneAndPrimaryContactAndContactVerifiedAtIsNull(phone, "PHONE");
        for (PPerson p : candidates) {
            PasswordReset row = resets.findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(p.id, PURPOSE)
                .filter(r -> "SMS".equals(r.channel) && r.expiresAt.isAfter(Instant.now()))
                .orElse(null);
            if (row == null) continue;
            if (row.attempts >= MAX_ATTEMPTS) {
                audit.record(null, p.tenantId, "ACTIVATION_REJECTED", p.id, "activationId=" + row.id + ";reason=attempts_exhausted");
                log.warn("idp_activation_rejected person={} reason=attempts_exhausted", p.id);
                continue;
            }
            if (IdpAuthService.constantTimeEquals(row.tokenHash, codeHash(p.id, code))) {
                return row;
            }
            resets.bumpAttempts(row.id);
            audit.record(null, p.tenantId, "ACTIVATION_REJECTED", p.id,
                "activationId=" + row.id + ";reason=code_mismatch;attempt=" + (row.attempts + 1));
            log.warn("idp_activation_rejected person={} reason=code_mismatch attempt={}", p.id, row.attempts + 1);
        }
        return null;
    }

    private void save(PasswordReset row) {
        try {
            resets.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            // a concurrent send won the one-live-row race; its link/code is on the way
            log.info("idp_activation_superseded person={}", row.personId);
            throw e;
        }
    }

    static String codeHash(UUID personId, String code) {
        return IdpAuthService.sha256Hex("activation:" + personId + ":" + code);
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_DIGITS);
        for (int i = 0; i < CODE_DIGITS; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }

    private String newToken() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    /** The origin-bound code line needs the bare host[:port] of the public origin. */
    private String host() {
        try {
            URI u = URI.create(props.getPublicBaseUrl());
            return u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
        } catch (RuntimeException e) {
            return "localhost";
        }
    }

    /** Same E.164 normalisation as core's ChannelAddresses (the IdP must not couple to core). */
    static String e164(String raw) {
        if (raw == null) return null;
        String compact = raw.replaceAll("[\\s()\\-.]", "");
        if (compact.startsWith("00")) compact = "+" + compact.substring(2);
        return compact.matches("^\\+[1-9]\\d{7,14}$") ? compact : null;
    }
}
