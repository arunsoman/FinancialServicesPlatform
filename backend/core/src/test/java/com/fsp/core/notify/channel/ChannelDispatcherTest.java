package com.fsp.core.notify.channel;

import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.notify.Notification;
import com.fsp.core.notify.NotificationChannelPref;
import com.fsp.core.notify.NotificationChannelPrefRepository;
import com.fsp.core.notify.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V29 channel dispatch rules: consent off / absent channel / no contact → claim via NONE
 * without ever calling the transport; delivered → claim with the delivery tag; definitive
 * unreachable → pref flagged dead; transient → bounded bump. Provider I/O is never
 * wrapped in the write transactions (outcomes drive short per-row claims).
 */
class ChannelDispatcherTest {

    private static final UUID PERSON = UUID.randomUUID();

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final PersonRepository persons = mock(PersonRepository.class);
    private final NotificationChannelPrefRepository prefs = mock(NotificationChannelPrefRepository.class);
    private final OutboundChannel email = mock(OutboundChannel.class);
    private final TransactionTemplate tx = tx();

    private ChannelDispatcher dispatcher() {
        return new ChannelDispatcher(notifications, persons, prefs,
            List.of(email), tx);
    }

    private Notification notification() {
        Notification n = new Notification();
        n.id = UUID.randomUUID();
        n.personId = PERSON;
        n.type = "APPROVAL_OPEN";
        n.title = "Awaiting approval";
        n.body = "A journal entry posting is waiting for a checker decision.";
        n.link = "/approvals";
        return n;
    }

    private NotificationChannelPref pref(boolean enabled, boolean dead) {
        NotificationChannelPref p = new NotificationChannelPref();
        p.personId = PERSON;
        p.channel = NotificationChannelPref.EMAIL;
        p.enabled = enabled;
        p.dead = dead;
        return p;
    }

    private Person person() {
        Person p = new Person();
        p.id = PERSON;
        p.email = "checker@example.com";
        return p;
    }

    @Test
    void consentOffOrAbsentClaimsNoneAndNeverTouchesTheTransport() {
        when(prefs.findByPersonIdAndChannel(any(), any())).thenReturn(Optional.empty());

        dispatcher().sweepChannel(ChannelWire.EMAIL, List.of(notification()));

        verify(email, never()).send(any(), any()); // dormant by design — opt-IN (§12.3)
        ArgumentCaptor<String> via = ArgumentCaptor.forClass(String.class);
        verify(notifications).claimEmailed(any(), any(), via.capture());
        assertEquals("NONE", via.getValue());
    }

    @Test
    void channelWithoutCredentialsClaimsNoneNeverDelaysTheRow() {
        when(prefs.findByPersonIdAndChannel(PERSON, ChannelWire.EMAIL)).thenReturn(Optional.of(pref(true, false)));

        new ChannelDispatcher(notifications, persons, prefs, List.of(), tx) // no beans: channel ABSENT
            .sweepChannel(ChannelWire.EMAIL, List.of(notification()));

        verify(email, never()).send(any(), any());
        verify(notifications).claimEmailed(any(), any(), org.mockito.ArgumentMatchers.eq("NONE"));
    }

    @Test
    void deliveryClaimsTagAndDefinitiveFailureFlagsContactDead() {
        when(prefs.findByPersonIdAndChannel(PERSON, ChannelWire.EMAIL)).thenReturn(Optional.of(pref(true, false)));
        when(persons.findById(PERSON)).thenReturn(Optional.of(person()));
        when(email.channel()).thenReturn(ChannelWire.EMAIL);
        when(email.deliveryTag()).thenReturn("SMTP");
        when(email.send(any(), any())).thenReturn(new ChannelWire.SendOutcome.Delivered());
        ChannelDispatcher d = dispatcher();

        d.sweepChannel(ChannelWire.EMAIL, List.of(notification()));
        verify(notifications).claimEmailed(any(), any(), org.mockito.ArgumentMatchers.eq("SMTP"));

        when(email.send(any(), any())).thenReturn(
            new ChannelWire.SendOutcome.Unreachable(true, "smtp_rejected"));
        d.sweepChannel(ChannelWire.EMAIL, List.of(notification()));
        verify(prefs).flagDead(org.mockito.ArgumentMatchers.eq(PERSON), org.mockito.ArgumentMatchers.eq(ChannelWire.EMAIL), any());
        verify(notifications).claimEmailed(any(), any(), org.mockito.ArgumentMatchers.eq("NONE"));
    }

    @Test
    void transientFailureBumpsBoundedAttempts() {
        when(prefs.findByPersonIdAndChannel(PERSON, ChannelWire.EMAIL)).thenReturn(Optional.of(pref(true, false)));
        when(persons.findById(PERSON)).thenReturn(Optional.of(person()));
        when(email.channel()).thenReturn(ChannelWire.EMAIL);
        when(email.deliveryTag()).thenReturn("SMTP");
        when(email.send(any(), any())).thenReturn(
            new ChannelWire.SendOutcome.Unreachable(false, "smtp_transient"));

        dispatcher().sweepChannel(ChannelWire.EMAIL, List.of(notification()));

        verify(notifications).bumpEmailAttempts(any());
        verify(notifications, never()).claimEmailed(any(), any(), any()); // stays queued
    }

    @Test
    void deadContactIsNeverSentToAgain() {
        when(prefs.findByPersonIdAndChannel(PERSON, ChannelWire.EMAIL)).thenReturn(Optional.of(pref(true, true)));

        dispatcher().sweepChannel(ChannelWire.EMAIL, List.of(notification()));

        verify(email, never()).send(any(), any());
        verify(notifications).claimEmailed(any(), any(), org.mockito.ArgumentMatchers.eq("NONE"));
    }

    /** TransactionTemplate backed by a no-op manager — the dispatcher's short per-row writes run inline. */
    private static TransactionTemplate tx() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        });
    }
}
