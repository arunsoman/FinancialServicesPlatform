package com.fsp.idp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V36 activation: an invited person gets a one-time proof on their PRIMARY contact (email link
 * / SMS code), sets their OWN password with it, and that stamps contact_verified_at. SMS never
 * carries a link secret and ends with the origin-bound code line; wrong codes count; the
 * proof is single-use; sign-in is refused until activation.
 */
class IdpActivationServiceTest {

    private final PPersonRepository persons = mock(PPersonRepository.class);
    private final PasswordResetRepository resets = mock(PasswordResetRepository.class);
    private final IdpAuthService auth = mock(IdpAuthService.class);
    private final IdpProperties props = new IdpProperties();
    private final IdpAudit audit = mock(IdpAudit.class);
    private final IdpActivationService service;

    IdpActivationServiceTest() {
        props.setPublicBaseUrl("https://portal.fsp.example");
        service = new IdpActivationService(persons, resets, auth, props, audit);
    }

    private PPerson person(String primary) {
        PPerson p = new PPerson();
        p.id = UUID.randomUUID();
        p.tenantId = UUID.randomUUID();
        p.username = "EMAIL".equals(primary) ? "jane@example.com" : "+264811234567";
        p.email = "jane@example.com";
        p.phone = "+264811234567";
        p.primaryContact = primary;
        p.status = "PENDING";
        p.isCustomer = true;
        p.passwordHash = "$2a$10$unguessable";
        when(persons.findById(p.id)).thenReturn(Optional.of(p));
        return p;
    }

    private String sentText(UUID personId, String channel) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(auth).dispatch(eq(personId), eq("ACTIVATION"), eq(channel), anyString(), text.capture());
        return text.getValue();
    }

    @Test
    void emailPrimaryGetsAOneTimeLinkAndOnlyItsHashIsStored() {
        PPerson p = person("EMAIL");
        service.send(p.id);

        ArgumentCaptor<PasswordReset> row = ArgumentCaptor.forClass(PasswordReset.class);
        verify(resets).consumePending(eq(p.id), any()); // a new send supersedes earlier ones
        verify(resets).saveAndFlush(row.capture());
        String text = sentText(p.id, "EMAIL");
        Matcher m = Pattern.compile("/idp/activate\\?token=([0-9a-f]{64})").matcher(text);
        assertTrue(m.find(), "the mail carries the activation link");
        assertEquals("ACTIVATION", row.getValue().purpose);
        assertEquals("EMAIL", row.getValue().channel);
        assertEquals(IdpAuthService.sha256Hex(m.group(1)), row.getValue().tokenHash);
        assertFalse(row.getValue().tokenHash.contains(m.group(1)));
        assertTrue(text.contains(p.username), "the person learns their sign-in name");
        // audited (system step, no actor) — the snapshot never holds the token
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(audit).record(isNull(), eq(p.tenantId), eq("ACTIVATION_ISSUED"), eq(p.id), state.capture());
        assertFalse(state.getValue().contains(m.group(1)), "the audit never holds the link secret");
    }

    @Test
    void phonePrimaryGetsAnSmsCodeWithTheOriginBoundLineAndNoSecretLink() {
        PPerson p = person("PHONE");
        service.send(p.id);

        String text = sentText(p.id, "SMS");
        Matcher code = Pattern.compile("@portal\\.fsp\\.example #(\\d{8})$").matcher(text);
        assertTrue(code.find(), "ends with the origin-bound one-time-code line");
        assertFalse(text.contains("token="), "no secret-bearing link over SMS");
        ArgumentCaptor<PasswordReset> row = ArgumentCaptor.forClass(PasswordReset.class);
        verify(resets).saveAndFlush(row.capture());
        assertEquals("SMS", row.getValue().channel);
        assertEquals(IdpActivationService.codeHash(p.id, code.group(1)), row.getValue().tokenHash);
    }

    @Test
    void nothingIsSentToSomeoneAlreadyActivated() {
        PPerson p = person("EMAIL");
        p.contactVerifiedAt = Instant.now();
        service.send(p.id);
        verify(auth, never()).dispatch(any(), anyString(), anyString(), anyString(), anyString());
        verify(resets, never()).saveAndFlush(any());
    }

    private PasswordReset liveRow(PPerson p, String channel, String hash) {
        PasswordReset r = new PasswordReset();
        r.id = UUID.randomUUID();
        r.personId = p.id;
        r.purpose = "ACTIVATION";
        r.channel = channel;
        r.tokenHash = hash;
        r.expiresAt = Instant.now().plusSeconds(600);
        return r;
    }

    @Test
    void theLinkSetsTheirOwnPasswordProvesTheEmailAndIsSingleUse() {
        PPerson p = person("EMAIL");
        String token = "ab".repeat(32);
        PasswordReset row = liveRow(p, "EMAIL", IdpAuthService.sha256Hex(token));
        when(resets.findFirstByTokenHashAndConsumedAtIsNullOrderByCreatedAtDesc(row.tokenHash)).thenReturn(Optional.of(row));

        var done = service.complete(new IdpActivationService.Proof.Link(token), "a-long-passphrase", "a-long-passphrase");

        assertNotNull(p.contactVerifiedAt);
        assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("a-long-passphrase", p.passwordHash));
        assertNotNull(row.consumedAt);
        assertFalse(done.signInNow(), "a customer still waits for the checker");
        // the person completed it themselves — they are the audit actor
        verify(audit).record(eq(p.id), eq(p.tenantId), eq("ACTIVATION_COMPLETED"), eq(p.id), anyString());
    }

    @Test
    void aResetTokenIsNotAnActivation() {
        PPerson p = person("EMAIL");
        String token = "cd".repeat(32);
        PasswordReset row = liveRow(p, "EMAIL", IdpAuthService.sha256Hex(token));
        row.purpose = "RESET";
        when(resets.findFirstByTokenHashAndConsumedAtIsNullOrderByCreatedAtDesc(row.tokenHash)).thenReturn(Optional.of(row));
        var e = assertThrows(IdpProblemException.class,
            () -> service.complete(new IdpActivationService.Proof.Link(token), "a-long-passphrase", "a-long-passphrase"));
        assertEquals("ACTIVATION_INVALID", e.problem().code());
        assertNull(p.contactVerifiedAt);
    }

    @Test
    void aWrongSmsCodeCountsAndChangesNothing() {
        PPerson p = person("PHONE");
        PasswordReset row = liveRow(p, "SMS", IdpActivationService.codeHash(p.id, "12345678"));
        when(persons.findByPhoneAndPrimaryContactAndContactVerifiedAtIsNull("+264811234567", "PHONE")).thenReturn(List.of(p));
        when(resets.findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(p.id, "ACTIVATION")).thenReturn(Optional.of(row));

        var e = assertThrows(IdpProblemException.class, () -> service.complete(
            new IdpActivationService.Proof.Code("+264 81 123 4567", "87654321"), "a-long-passphrase", "a-long-passphrase"));
        assertEquals("ACTIVATION_INVALID", e.problem().code());
        verify(resets).bumpAttempts(row.id);
        verify(audit).record(isNull(), eq(p.tenantId), eq("ACTIVATION_REJECTED"), eq(p.id), anyString());
        assertNull(p.contactVerifiedAt);

        var ok = service.complete(new IdpActivationService.Proof.Code("+264 81 123 4567", "12345678"),
            "a-long-passphrase", "a-long-passphrase");
        assertNotNull(p.contactVerifiedAt);
        assertEquals("+264811234567", ok.username());
    }

    @Test
    void attemptsAreCapped() {
        PPerson p = person("PHONE");
        PasswordReset row = liveRow(p, "SMS", IdpActivationService.codeHash(p.id, "12345678"));
        row.attempts = IdpActivationService.MAX_ATTEMPTS;
        when(persons.findByPhoneAndPrimaryContactAndContactVerifiedAtIsNull("+264811234567", "PHONE")).thenReturn(List.of(p));
        when(resets.findFirstByPersonIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(p.id, "ACTIVATION")).thenReturn(Optional.of(row));
        assertThrows(IdpProblemException.class, () -> service.complete(
            new IdpActivationService.Proof.Code("+264811234567", "12345678"), "a-long-passphrase", "a-long-passphrase"));
        assertNull(p.contactVerifiedAt, "even the right code is refused once exhausted");
    }
}
