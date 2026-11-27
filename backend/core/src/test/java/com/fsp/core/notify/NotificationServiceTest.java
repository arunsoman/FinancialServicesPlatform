package com.fsp.core.notify;

import com.fsp.core.common.ApiException;
import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.RbacRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-12 notification writer rules: checker plane pointed at the open approval, maker
 * excluded (four-eyes §3.1), per-resource dedupe, generic PII-free copy (§2c), no-op
 * when nobody holds the checker key.
 */
class NotificationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MAKER = UUID.randomUUID();
    private static final UUID CHECKER = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();

    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final PushSubscriptionRepository subs = mock(PushSubscriptionRepository.class);
    private final NotificationChannelPrefRepository prefs = mock(NotificationChannelPrefRepository.class);
    private final RbacRepository rbac = mock(RbacRepository.class);
    private final PersonRepository persons = mock(PersonRepository.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final NotificationService svc = new NotificationService(repo, subs, prefs, persons, rbac, outbox);

    private Person person(UUID id) {
        Person p = new Person();
        p.id = id;
        p.tenantId = TENANT;
        p.status = "ACTIVE";
        return p;
    }

    @Test
    void channelToggleRequiresProfileContactToEnable() {
        when(persons.findById(CHECKER)).thenReturn(Optional.of(person(CHECKER))); // no email/phone set
        ApiException noContact = assertThrows(ApiException.class,
            () -> svc.setChannels(CHECKER, Boolean.TRUE, null));
        assertEquals("CHANNEL_NO_CONTACT", noContact.getCode());
        verify(prefs, never()).save(any());
    }

    @Test
    void channelEnableWritesConsentAndDisableIsIdempotent() {
        Person withContact = person(CHECKER);
        withContact.email = "checker@example.com";
        when(persons.findById(CHECKER)).thenReturn(Optional.of(withContact));
        when(prefs.findByPersonIdAndChannel(CHECKER, NotificationChannelPref.EMAIL)).thenReturn(Optional.empty());

        svc.setChannels(CHECKER, Boolean.TRUE, null);

        ArgumentCaptor<NotificationChannelPref> cap = ArgumentCaptor.forClass(NotificationChannelPref.class);
        verify(prefs, times(1)).save(cap.capture());
        assertTrue(cap.getValue().enabled);
        assertEquals(NotificationChannelPref.EMAIL, cap.getValue().channel);
        assertTrue(!cap.getValue().dead); // enabling never flips the bounce flag — contact is live

        // disabling a consent that was never written: idempotent no-op, no insert
        org.mockito.Mockito.reset(prefs);
        when(prefs.findByPersonIdAndChannel(CHECKER, NotificationChannelPref.SMS)).thenReturn(Optional.empty());
        svc.setChannels(CHECKER, null, Boolean.FALSE);
        verify(prefs, never()).save(any());
    }

    @Test
    void channelStateNeverExposesTheRawContact() {
        Person withContact = person(CHECKER);
        withContact.email = "checker@example.com"; // secret-ish value that must NOT go out
        when(persons.findById(CHECKER)).thenReturn(Optional.of(withContact));
        when(prefs.findByPersonIdAndChannel(CHECKER, NotificationChannelPref.EMAIL)).thenReturn(Optional.empty());
        when(prefs.findByPersonIdAndChannel(CHECKER, NotificationChannelPref.SMS)).thenReturn(Optional.empty());

        List<NotificationService.ChannelState> states = svc.channelState(CHECKER);

        assertEquals(2, states.size());
        assertTrue(states.get(0).hasContact()); // EMAIL present
        assertTrue(!states.get(1).hasContact()); // SMS absent
        assertTrue(!states.get(0).enabled()); // dormant by default — opt-IN
    }

    @Test
    void notifiesCheckerPlaneButNeverTheMaker() {
        when(rbac.activePersonsWithPermission(TENANT, "je.approve"))
            .thenReturn(List.of(person(MAKER), person(CHECKER)));
        when(repo.existsByPersonIdAndTypeAndResourceId(any(), any(), any())).thenReturn(false);

        svc.notifyCheckers(TENANT, "JE_POSTING", "je.approve", RESOURCE, MAKER);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(repo, times(1)).save(cap.capture());
        assertEquals(CHECKER, cap.getValue().personId);
        assertEquals(NotificationService.TYPE_APPROVAL_OPEN, cap.getValue().type);
        assertEquals("JE_POSTING", cap.getValue().opType);
        assertEquals(RESOURCE, cap.getValue().resourceId);
        // §2c: generic copy — no payload data, no names
        assertEquals("Awaiting approval", cap.getValue().title);
        assertTrue(cap.getValue().body.contains("journal entry posting"));
        verify(outbox, times(1)).record(eq("Notification"), eq(RESOURCE),
            eq("Notification.PushRequired"), any());
    }

    @Test
    void duplicatesAreSuppressed() {
        when(rbac.activePersonsWithPermission(TENANT, "je.approve")).thenReturn(List.of(person(CHECKER)));
        when(repo.existsByPersonIdAndTypeAndResourceId(eq(CHECKER), any(), any())).thenReturn(true);

        svc.notifyCheckers(TENANT, "JE_POSTING", "je.approve", RESOURCE, MAKER);

        verify(repo, never()).save(any());
        verify(outbox, never()).record(any(), any(), any(), any());
    }

    @Test
    void noHoldersMeansSilentNoOp() {
        when(rbac.activePersonsWithPermission(TENANT, "bank.account.approve")).thenReturn(List.of());

        svc.notifyCheckers(TENANT, "BANK_ACCOUNT_CREATE", "bank.account.approve", RESOURCE, MAKER);

        verify(repo, never()).save(any());
        verify(outbox, never()).record(any(), any(), any(), any());
    }

    @Test
    void subscribeRejectsBadPlatformAndMissingMaterial() {
        ApiException bad = assertThrows(ApiException.class,
            () -> svc.subscribe(CHECKER, "SMS", "https://push.example/x", null, null, null, "ua"));
        assertEquals("PUSH_PLATFORM", bad.getCode());

        ApiException noEndpoint = assertThrows(ApiException.class,
            () -> svc.subscribe(CHECKER, "WEBPUSH", "http://insecure.example/x", null, null, null, "ua"));
        assertEquals("PUSH_ENDPOINT", noEndpoint.getCode());

        ApiException noToken = assertThrows(ApiException.class,
            () -> svc.subscribe(CHECKER, "APNS", null, " ", null, null, "ua"));
        assertEquals("PUSH_TOKEN", noToken.getCode());
    }

    @Test
    void subscribeRefreshesKnownDeviceInPlace() {
        when(persons.findById(CHECKER)).thenReturn(Optional.of(person(CHECKER)));
        PushSubscription existing = new PushSubscription();
        existing.id = UUID.randomUUID();
        existing.personId = CHECKER;
        existing.tenantId = TENANT;
        existing.platform = "WEBPUSH";
        existing.endpoint = "https://push.example/x";
        existing.status = "ACTIVE";
        when(subs.findByEndpointAndStatus("https://push.example/x", "ACTIVE"))
            .thenReturn(Optional.of(existing));

        PushSubscription row = svc.subscribe(CHECKER, "WEBPUSH", "https://push.example/x",
            null, "newp256dh", "newauth", "ua/2");

        assertEquals(existing.id, row.id); // one identity per device — refreshed, not duplicated
        assertEquals("newp256dh", row.p256dh);
        assertEquals("newauth", row.auth);
        verify(subs, times(1)).save(existing);
    }
}
