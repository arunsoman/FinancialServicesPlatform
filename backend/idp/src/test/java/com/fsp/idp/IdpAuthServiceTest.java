package com.fsp.idp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V30 auth-plane flows at the IdP: silent reset requests (no enumeration), hashed single-use
 * reset tokens, OTP challenges with cooldown + attempt caps, enrolment possession proof,
 * and one dispatch row parked for core's AuthDispatchDispatcher per secret message.
 */
class IdpAuthServiceTest {

    private final PPersonRepository persons = mock(PPersonRepository.class);
    private final PasswordResetRepository resets = mock(PasswordResetRepository.class);
    private final MfaChallengeRepository challenges = mock(MfaChallengeRepository.class);
    private final UserMfaRepository mfas = mock(UserMfaRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdpProperties props = new IdpProperties();

    private final IdpAuthService auth =
        new IdpAuthService(persons, resets, challenges, mfas, jdbc, props);

    private PPerson person(String email, String phone) {
        PPerson p = new PPerson();
        p.id = UUID.randomUUID();
        p.tenantId = UUID.randomUUID();
        p.username = "maker";
        p.fullName = "Maker Person";
        p.passwordHash = "$2a$10$oldHash";
        p.status = "ACTIVE";
        p.email = email;
        p.phone = phone;
        return p;
    }

    // ---------- password reset ----------

    @Test
    void unknownPersonIsSilentNoRowNoDispatch() {
        when(persons.findByUsername("ghost")).thenReturn(Optional.empty());

        auth.requestPasswordReset("ghost");

        verify(resets, never()).save(any());
        verifyNoInteractions(jdbc);
    }

    @Test
    void resetRequestStoresHashedTokenAndParksDispatch() {
        PPerson p = person("ops@tenant.example", null);
        when(persons.findByUsername("maker")).thenReturn(Optional.of(p));

        auth.requestPasswordReset("maker");

        // supersede old pending resets, persist exactly one row with a HASHED token
        verify(resets).consumePending(eq(p.id), any());
        ArgumentCaptor<PasswordReset> row = ArgumentCaptor.forClass(PasswordReset.class);
        verify(resets).save(row.capture());
        assertEquals(64, row.getValue().tokenHash.length());
        // one dispatch row parked for the notify plane (+ one outbox evidence row)
        verify(jdbc).update(startsWith("insert into fsp_notifications"), any(), any(), any(), any(), any(), any(), any());
        verify(jdbc).update(startsWith("insert into outbox_events"), any(), any(), any());
        assertTrue(row.getValue().expiresAt.isAfter(Instant.now().plusSeconds(14 * 60)));
    }

    @Test
    void resetWithoutUsableEmailDoesNothing() {
        PPerson p = person(null, "+15550001");
        when(persons.findByUsername("maker")).thenReturn(Optional.of(p));

        auth.requestPasswordReset("maker");

        verify(resets, never()).save(any());
        verifyNoInteractions(jdbc);
    }

    @Test
    void resetConfirmConsumesTokenAndFlipsMustChange() {
        PPerson p = person("ops@tenant.example", null);
        PasswordReset row = new PasswordReset();
        row.id = UUID.randomUUID();
        row.personId = p.id;
        row.tokenHash = IdpAuthService.sha256Hex("goodtok");
        row.expiresAt = Instant.now().plusSeconds(60);
        row.attempts = 0;
        when(persons.findById(p.id)).thenReturn(Optional.of(p));
        when(resets.findFirstByTokenHashAndConsumedAtIsNullOrderByCreatedAtDesc(row.tokenHash))
            .thenReturn(Optional.of(row));

        auth.confirmPasswordReset("goodtok", "brand-new-password-10", "brand-new-password-10");

        assertEquals("$2a", p.passwordHash.substring(0, 3));
        assertFalse(p.mustChangePassword);
        assertFalse(row.consumedAt == null);
    }

    @Test
    void resetConfirmRejectsUnknownOrExpiredTokenWithoutDetails() {
        when(resets.findFirstByTokenHashAndConsumedAtIsNullOrderByCreatedAtDesc(anyString()))
            .thenReturn(Optional.empty());

        IdpProblemException e = assertThrows(IdpProblemException.class,
            () -> auth.confirmPasswordReset("bogus", "brand-new-password-10", "brand-new-password-10"));
        assertEquals("RESET_TOKEN_INVALID", e.problem().code());
    }

    @Test
    void resetConfirmEnforcesPolicyAndConfirmation() {
        IdpProblemException e1 = assertThrows(IdpProblemException.class,
            () -> auth.confirmPasswordReset("t", "short", "short"));
        assertEquals("RESET_POLICY_LENGTH", e1.problem().code());

        IdpProblemException e2 = assertThrows(IdpProblemException.class,
            () -> auth.confirmPasswordReset("t", "long-enough-password", "different"));
        assertEquals("RESET_CONFIRM_MISMATCH", e2.problem().code());
    }

    // ---------- MFA challenges + second factor ----------

    @Test
    void firstLoginAttemptIssuesChallengeAndDispatches() {
        UUID personId = UUID.randomUUID();
        UserMfa mfa = new UserMfa();
        mfa.personId = personId;
        mfa.channel = UserMfa.EMAIL;
        when(mfas.findByPersonId(personId)).thenReturn(Optional.of(mfa));
        when(challenges.findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            personId, MfaChallenge.LOGIN)).thenReturn(Optional.empty());

        IdpProblemException e = assertThrows(IdpProblemException.class,
            () -> auth.requireSecondFactor(personId, null));
        assertEquals("MFA_REQUIRED", e.problem().code());

        verify(challenges).consumePending(eq(personId), eq(MfaChallenge.LOGIN), any());
        ArgumentCaptor<MfaChallenge> row = ArgumentCaptor.forClass(MfaChallenge.class);
        verify(challenges).save(row.capture());
        assertEquals(64, row.getValue().codeHash.length());
        assertTrue(row.getValue().expiresAt.isAfter(Instant.now().plusSeconds(240)));
        // one dispatch row parked for the notify plane + one outbox evidence row
        verify(jdbc).update(startsWith("insert into fsp_notifications"), any(), any(), any(), any(), any(), any(), any());
        verify(jdbc).update(startsWith("insert into outbox_events"), any(), any(), any());
    }

    @Test
    void challengeInsideCooldownIsReusedNotReDispatched() {
        UUID personId = UUID.randomUUID();
        UserMfa mfa = new UserMfa();
        mfa.personId = personId;
        mfa.channel = UserMfa.SMS;
        when(mfas.findByPersonId(personId)).thenReturn(Optional.of(mfa));
        MfaChallenge live = new MfaChallenge();
        live.id = UUID.randomUUID();
        live.personId = personId;
        live.purpose = MfaChallenge.LOGIN;
        live.channel = UserMfa.SMS;
        live.codeHash = "0".repeat(64);
        live.createdAt = Instant.now();
        live.expiresAt = Instant.now().plusSeconds(IdpAuthService.OTP_SECONDS);
        when(challenges.findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            personId, MfaChallenge.LOGIN)).thenReturn(Optional.of(live));

        assertThrows(IdpProblemException.class, () -> auth.requireSecondFactor(personId, null));

        verify(challenges, never()).save(any());
        verifyNoInteractions(jdbc);
    }

    @Test
    void wrongCodeBumpsAttemptsAndRejects() {
        UUID personId = UUID.randomUUID();
        MfaChallenge c = new MfaChallenge();
        c.id = UUID.randomUUID();
        c.personId = personId;
        c.purpose = MfaChallenge.LOGIN;
        c.channel = UserMfa.EMAIL;
        c.codeHash = IdpAuthService.sha256Hex("12345678");
        c.expiresAt = Instant.now().plusSeconds(60);
        c.attempts = 0;
        when(challenges.findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            personId, MfaChallenge.LOGIN)).thenReturn(Optional.of(c));

        IdpProblemException e = assertThrows(IdpProblemException.class,
            () -> auth.verifyLoginCode(personId, "99999999"));
        assertEquals("MFA_CODE_INVALID", e.problem().code());
        verify(challenges).bumpAttempts(c.id);
        verify(challenges, never()).save(any());
    }

    @Test
    void exhaustedCodeAttemptsConsumeTheChallenge() {
        UUID personId = UUID.randomUUID();
        MfaChallenge c = new MfaChallenge();
        c.id = UUID.randomUUID();
        c.personId = personId;
        c.purpose = MfaChallenge.LOGIN;
        c.channel = UserMfa.EMAIL;
        c.codeHash = "0".repeat(64);
        c.expiresAt = Instant.now().plusSeconds(60);
        c.attempts = 3;
        when(challenges.findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            personId, MfaChallenge.LOGIN)).thenReturn(Optional.of(c));

        IdpProblemException e = assertThrows(IdpProblemException.class,
            () -> auth.verifyLoginCode(personId, "12345678"));
        assertEquals("MFA_ATTEMPTS_EXHAUSTED", e.problem().code());
        verify(challenges).consumePending(eq(personId), eq(MfaChallenge.LOGIN), any());
    }

    // ---------- enrolment ----------

    @Test
    void enrolmentRequiresProfileContact() {
        PPerson p = person(null, null);
        when(persons.findById(p.id)).thenReturn(Optional.of(p));

        IdpProblemException e = assertThrows(IdpProblemException.class,
            () -> auth.beginEnrolment(p.id, UserMfa.SMS));
        assertEquals("MFA_NO_CONTACT", e.problem().code());
        verify(challenges, never()).save(any());
    }

    @Test
    void enrolmentConfirmUpsertsUserMfaWithChallengeChannel() {
        UUID personId = UUID.randomUUID();
        MfaChallenge pending = new MfaChallenge();
        pending.id = UUID.randomUUID();
        pending.personId = personId;
        pending.purpose = MfaChallenge.ENROLMENT;
        pending.channel = UserMfa.SMS;
        pending.codeHash = IdpAuthService.sha256Hex("45219876");
        pending.createdAt = Instant.now().minusSeconds(10);
        pending.expiresAt = Instant.now().plusSeconds(200);
        when(challenges.findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            personId, MfaChallenge.ENROLMENT)).thenReturn(Optional.of(pending));

        auth.confirmEnrolment(personId, "45219876");

        assertTrue(pending.consumedAt != null);
        ArgumentCaptor<UserMfa> saved = ArgumentCaptor.forClass(UserMfa.class);
        verify(mfas).save(saved.capture());
        assertEquals(UserMfa.SMS, saved.getValue().channel);
        assertEquals(personId, saved.getValue().personId);
    }

    @Test
    void disableRequiresCurrentPassword() {
        PPerson p = person("ops@tenant.example", null);
        p.passwordHash = IdpAuthService.sha256Hex("not-bcrypt"); // bcrypt.matches fails for this
        when(persons.findById(p.id)).thenReturn(Optional.of(p));

        IdpProblemException e = assertThrows(IdpProblemException.class,
            () -> auth.disable(p.id, "current-password"));
        assertEquals("INVALID_CREDENTIALS", e.problem().code());
        verify(mfas, never()).delete(any());
    }

}
