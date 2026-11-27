package com.fsp.idp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Auth-plane flows that need a second channel (V30; chapter 02 §7 and chapter 12 §12.9):
 *
 *  - self-service PASSWORD RESET (pre-auth): a one-time token (hashed, single-use, 15 min)
 *    travels through the notification EMAIL transport — SMS is deliberately NOT used for
 *    reset (no links over SMS per §12.4, and a reset needs the link);
 *  - MFA second factor (login OTP / enrolment OTP): possession codes through EMAIL or SMS,
 *    hashed at rest, ≤3 attempts per challenge, 60 s issuance cooldown, 5 min TTL.
 *
 * This service OWNS the secrets and their state machines ({@code password_resets},
 * {@code mfa_challenges}, {@code user_mfa} — all in public, core-migrated like V6).
 * It does NOT own transports: rendering a message parks one row on
 * {@code fsp_notifications.auth_dispatches} (plus the outbox evidence row) and core's
 * {@code AuthDispatchDispatcher} delivers it with the same SMTP/SMS beans as F-12.
 * One-way coupling: the IdP only writes dispatch rows, never reads them back.
 *
 * Enumeration discipline: reset-request always answers 204 whatever the username (§9700).
 */
@Service
public class IdpAuthService {

    static final int RESET_TTL_MINUTES = 15;
    static final int RESET_MAX_ATTEMPTS = 5;
    static final int RESET_MIN_LENGTH = 10;   // mirrors F-8 policy (IdpController PW_*)
    static final int RESET_MAX_LENGTH = 128;
    static final int OTP_SECONDS = 300;
    static final int OTP_MAX_ATTEMPTS = 3;
    static final int OTP_DIGITS = 8;
    static final int CHALLENGE_COOLDOWN_SECONDS = 60;

    private static final Logger log = LoggerFactory.getLogger(IdpAuthService.class);
    private final SecureRandom random = new SecureRandom();
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bcrypt =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    private final PPersonRepository persons;
    private final PasswordResetRepository resets;
    private final MfaChallengeRepository challenges;
    private final UserMfaRepository mfas;
    private final JdbcTemplate jdbc;
    private final IdpProperties props;
    /** the append-only trail, in the same transaction as each credential change */
    private final IdpAudit audit;

    public IdpAuthService(PPersonRepository persons, PasswordResetRepository resets,
                          MfaChallengeRepository challenges, UserMfaRepository mfas,
                          JdbcTemplate jdbc, IdpProperties props) {
        this.persons = persons;
        this.resets = resets;
        this.challenges = challenges;
        this.mfas = mfas;
        this.jdbc = jdbc;
        this.props = props;
        this.audit = new IdpAudit(jdbc);
    }

    // ============ self-service password reset (pre-auth) ============

    /** Always a silent no-op result for unknown/inactive/no-email persons (204 at the controller). */
    @Transactional
    public void requestPasswordReset(String username) {
        PPerson person = persons.findByUsername(username).orElse(null);
        if (person == null || !person.status.equals("ACTIVE")) {
            log.info("idp_reset_requested outcome=silent username_provided=true");
            return;
        }
        if (person.primaryContact != null && person.contactVerifiedAt == null) {
            // V36: not activated yet — the activation (re-sent by staff) is the only way in
            log.info("idp_reset_requested outcome=silent reason=awaiting_activation person={}", person.id);
            return;
        }
        if (person.email == null || !plausibleEmail(person.email)) {
            // Reset NEEDS the link; never send secrets over SMS (§12.4) — nothing to do but log.
            log.warn("idp_reset_unreachable person={} reason=no_usable_email", person.id);
            return;
        }
        Instant now = Instant.now();
        resets.consumePending(person.id, now); // one live reset per person (V30 partial uq)
        String token = newHexToken();
        PasswordReset row = new PasswordReset();
        row.id = UUID.randomUUID();
        row.personId = person.id;
        row.tokenHash = sha256Hex(token);
        row.expiresAt = now.plusSeconds(RESET_TTL_MINUTES * 60L);
        try {
            resets.save(row);
        } catch (DataIntegrityViolationException e) {
            // a concurrent duplicate request won the partial unique — its reset is live;
            // this request simply stops (the person already has one valid code on the way).
            // Do NOT dispatch: the tx rolls back our (already-consumed) state cleanly.
            log.info("idp_reset_superseded person={}", person.id);
            throw e; // rolls back consumePending too; 500 is invisible (204-always surface)
        }

        dispatch(person.id, "PASSWORD_RESET", "EMAIL",
            "Reset your FSP password",
            "Someone (hopefully you) asked to reset the password of your FSP platform account.\n\n"
                + "This one-time link works for " + RESET_TTL_MINUTES + " minutes and can be opened once:\n\n"
                + props.getResetUrlBase() + "/#/reset?token=" + token + "\n\n"
                + "If you did not request this, ignore the mail — your password stays as it is.");
        audit.record(null, person.tenantId, "PASSWORD_RESET_REQUESTED", person.id,
            "channel=EMAIL;resetId=" + row.id + ";expiresAt=" + row.expiresAt);
        log.info("idp_reset_requested person={} channel=EMAIL expiresInMin={}", person.id, RESET_TTL_MINUTES);
    }

    @Transactional
    public void confirmPasswordReset(String token, String newPassword, String confirmPassword) {
        // confirmation-mismatch and policy failures do NOT consume the token: the person retries
        if (token == null || token.isBlank()) {
            throw problem("RESET_TOKEN_INVALID", "Reset link invalid or expired", 400);
        }
        if (!newPassword.equals(confirmPassword)) {
            throw problem("RESET_CONFIRM_MISMATCH", "The two passwords do not match", 400);
        }
        passwordPolicy(newPassword);
        PasswordReset row = resets
            .findFirstByTokenHashAndConsumedAtIsNullOrderByCreatedAtDesc(sha256Hex(token.trim()))
            .orElse(null);
        // V36: an activation token is never a reset token (different page, different effects)
        if (row == null || !"RESET".equals(row.purpose) || row.expiresAt.isBefore(Instant.now())) {
            throw problem("RESET_TOKEN_INVALID", "Reset link invalid or expired", 400);
        }
        if (row.attempts >= RESET_MAX_ATTEMPTS) {
            throw problem("RESET_ATTEMPTS_EXHAUSTED", "Too many attempts; request a new link", 400);
        }
        PPerson person = persons.findById(row.personId).orElse(null);
        if (person == null || !"ACTIVE".equals(person.status)) {
            throw problem("RESET_ACCOUNT_INACTIVE", "Account is not active", 403);
        }
        if (bcrypt.matches(newPassword, person.passwordHash)) {
            throw problem("RESET_SAME_PASSWORD", "Choose a password you have not used here before", 400);
        }
        person.passwordHash = bcrypt.encode(newPassword);
        person.mustChangePassword = false;
        persons.save(person);
        row.consumedAt = Instant.now();
        resets.save(row);
        audit.record(person.id, person.tenantId, "PASSWORD_RESET_COMPLETED", person.id,
            "resetId=" + row.id + ";at=" + Instant.now());
        log.info("idp_reset_confirmed person={}", person.id);
    }

    // ============ MFA: state + second factor on the token grant ============

    public UserMfa mfaFor(UUID personId) {
        return mfas.findByPersonId(personId).orElse(null);
    }

    /** The second factor of a native password grant: ensure a live challenge (dispatch when
     *  fresh), then require and verify the code from the grant body. */
    @Transactional
    public void requireSecondFactor(UUID personId, String otp) {
        UserMfa mfa = mfaFor(personId);
        if (mfa == null) return;
        if (otp == null || otp.isBlank()) {
            issueChallenge(personId, MfaChallenge.LOGIN, mfa.channel, true);
            throw problem("MFA_REQUIRED", "Verification code required; we sent one to your "
                + mfa.channel.toLowerCase() + " contact", 401);
        }
        verify(personId, MfaChallenge.LOGIN, otp.trim());
    }

    // ============ MFA: self-service enrolment (bearer-authenticated) ============

    public record MfaState(String channel, boolean hasEmail, boolean hasPhone) {}

    public MfaState state(UUID personId) {
        PPerson person = persons.findById(personId).orElse(null);
        UserMfa mfa = mfaFor(personId);
        return new MfaState(mfa == null ? null : mfa.channel,
            person != null && plausibleEmail(person.email),
            person != null && person.phone != null && !person.phone.isBlank());
    }

    /** Start enrolment: a confirmation code goes to the CHOSEN channel (possession proof). */
    @Transactional
    public void beginEnrolment(UUID personId, String channel) {
        if (!channel.equals(UserMfa.EMAIL) && !channel.equals(UserMfa.SMS)) {
            throw problem("MFA_CHANNEL_INVALID", "channel must be EMAIL or SMS", 400);
        }
        PPerson person = persons.findById(personId).orElse(null);
        if (person == null || !"ACTIVE".equals(person.status)) {
            throw problem("PERSON_INACTIVE", "Account is not active", 403);
        }
        boolean email = channel.equals(UserMfa.EMAIL);
        String contact = email ? person.email : person.phone;
        if (contact == null || contact.isBlank()
            || (email && !plausibleEmail(contact))) {
            throw problem("MFA_NO_CONTACT",
                "set your profile " + channel.toLowerCase() + " contact before enrolling this channel", 400);
        }
        issueChallenge(personId, MfaChallenge.ENROLMENT, channel, true);
        log.info("idp_mfa_enrolment_started person={} channel={}", personId, channel);
    }

    @Transactional
    public void confirmEnrolment(UUID personId, String code) {
        MfaChallenge c = verify(personId, MfaChallenge.ENROLMENT, code);
        Instant now = Instant.now();
        UserMfa row = mfaFor(personId);
        if (row == null) {
            row = new UserMfa();
            row.personId = personId;
            row.createdAt = now;
        }
        row.channel = c.channel;
        row.updatedAt = now;
        mfas.save(row);
        log.info("idp_mfa_enrolled person={} channel={}", personId, c.channel);
    }

    /** Disable requires the current password (knowledge proof) — FAPI-consistent factor removal. */
    @Transactional
    public void disable(UUID personId, String password) {
        PPerson person = persons.findById(personId).orElse(null);
        if (person == null || password == null || !bcrypt.matches(password, person.passwordHash)) {
            throw problem("INVALID_CREDENTIALS", "Current password required to disable MFA", 401);
        }
        if (mfas.findByPersonId(personId).orElse(null) instanceof UserMfa existing) {
            mfas.delete(existing);
        }
        challenges.consumePending(personId, MfaChallenge.ENROLMENT, Instant.now());
        log.info("idp_mfa_disabled person={}", personId);
    }

    // ============ challenge mechanics ============

    /** One live challenge per person+purpose; re-requests inside the cooldown reuse it (no pump). */
    private void issueChallenge(UUID personId, String purpose, String channel, boolean dispatch) {
        Instant now = Instant.now();
        MfaChallenge live = challenges
            .findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(personId, purpose)
            .orElse(null);
        boolean fresh = live != null && live.createdAt.isAfter(now.minusSeconds(CHALLENGE_COOLDOWN_SECONDS))
            && live.expiresAt.isAfter(now);
        if (fresh) return; // reuse: never let failed logins pump mail/SMS
        challenges.consumePending(personId, purpose, now);
        String code = String.format("%0" + OTP_DIGITS + "d", random.nextInt(10_0000_0000));
        MfaChallenge row = new MfaChallenge();
        row.id = UUID.randomUUID();
        row.personId = personId;
        row.purpose = purpose;
        row.channel = channel;
        row.codeHash = sha256Hex(code);
        row.expiresAt = now.plusSeconds(OTP_SECONDS);
        challenges.save(row);
        if (dispatch) {
            dispatch(personId, "MFA_" + purpose, channel,
                "FSP verification code",
                "Your FSP verification code is " + code + ". It works for "
                    + (OTP_SECONDS / 60) + " minutes. If it did not come from you, change your password.");
        }
        log.info("idp_mfa_challenge_issued person={} purpose={} channel={}", personId, purpose, channel);
    }

    /** Verify + consume the latest pending challenge for the purpose; returns it (for enrolment channel). */
    private MfaChallenge verify(UUID personId, String purpose, String rawCode) {
        MfaChallenge c = challenges
            .findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(personId, purpose)
            .orElseThrow(() -> problem("MFA_CODE_INVALID", "No pending verification code", 400));
        if (c.attempts >= OTP_MAX_ATTEMPTS) {
            challenges.consumePending(personId, purpose, Instant.now());
            throw problem("MFA_ATTEMPTS_EXHAUSTED", "Too many attempts; request a new code", 400);
        }
        if (c.expiresAt.isBefore(Instant.now())) {
            challenges.consumePending(personId, purpose, Instant.now());
            throw problem("MFA_CODE_EXPIRED", "Verification code expired; request a new one", 400);
        }
        if (!constantTimeEquals(sha256Hex(rawCode), c.codeHash)) {
            challenges.bumpAttempts(c.id);
            log.warn("idp_mfa_code_rejected person={} purpose={} attempts={}", personId, purpose, c.attempts + 1);
            throw problem("MFA_CODE_INVALID", "Verification code rejected", 400);
        }
        c.consumedAt = Instant.now();
        challenges.save(c);
        return c;
    }

    // ============ MFA helpers for the hosted flows ============

    /** Verify a LOGIN code outside the token grant (hosted MFA step after a password verification). */
    @Transactional
    public void verifyLoginCode(UUID personId, String code) {
        verify(personId, MfaChallenge.LOGIN, code);
    }

    // ============ cross-plane handoff: dispatch row + outbox evidence, same transaction ============

    /** Park ONE delivery row for core's AuthDispatchDispatcher + the outbox evidence row (AGENTS §2.5). */
    void dispatch(UUID personId, String purpose, String channel, String subject, String text) {
        UUID id = UUID.randomUUID();
        String trace = MDC.get("traceId");
        jdbc.update("""
            insert into fsp_notifications.auth_dispatches
                (id, purpose, person_id, channel, subject, text, correlation_id, created_at)
            values (?, ?, ?, ?, ?, ?, ?, now())
            """,
            id, purpose, personId, channel, subject, text, trace == null ? "idp-" + id : trace);
        jdbc.update("""
            insert into outbox_events (aggregate_type, aggregate_id, event_name, payload, correlation_id, created_at)
            values ('PERSON', ?, 'Notification.AuthDispatchRequired', ?::jsonb, ?, now())
            """,
            personId, "{\"purpose\":\"" + purpose + "\",\"channel\":\"" + channel + "\"}",
            trace == null ? "idp-" + id : trace);
    }

    // ============ misc ============

    private String newHexToken() {
        byte[] raw = new byte[32]; // 256-bit one-time token, single-use, hashed at rest
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static IdpProblemException problem(String code, String detail, int status) {
        return new IdpProblemException(new IdpDtos.IdpProblem(code, detail, status));
    }

    /** F-8 password policy, mirrored for the self-service reset (10–128 chars). */
    static void passwordPolicy(String newPassword) {
        if (newPassword == null || newPassword.length() < RESET_MIN_LENGTH
            || newPassword.length() > RESET_MAX_LENGTH) {
            throw problem("RESET_POLICY_LENGTH",
                "Password must be " + RESET_MIN_LENGTH + "–" + RESET_MAX_LENGTH + " characters", 400);
        }
    }

    /** Same shape check as the notify plane's ChannelAddresses.isPlausibleEmail —
     *  local copy because the IdP must not couple to core classes. */
    static boolean plausibleEmail(String email) {
        return email != null && email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }
}
