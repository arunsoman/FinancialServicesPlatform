package com.fsp.core.notify.channel;

import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.notify.AuthDispatch;
import com.fsp.core.notify.AuthDispatchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V30 auth dispatch rules: no channel bean / no contact → claim via NONE (secret purged in
 * the same statement); delivered → claim with the delivery tag; definitive unreachable →
 * claim DEAD; transient → bounded bump. No consent path exists on purpose — auth messages
 * are never opt-out (§12.9).
 */
class AuthDispatchDispatcherTest {

    private static final UUID PERSON = UUID.randomUUID();

    private final AuthDispatchRepository dispatches = mock(AuthDispatchRepository.class);
    private final PersonRepository persons = mock(PersonRepository.class);
    private final OutboundChannel email = mock(OutboundChannel.class);
    private final OutboundChannel sms = mock(OutboundChannel.class);
    private final com.fsp.core.audit.AuditService audit = mock(com.fsp.core.audit.AuditService.class);

    private AuthDispatchDispatcher dispatcher() {
        return new AuthDispatchDispatcher(dispatches, persons, List.of(email, sms), tx(), audit);
    }

    private AuthDispatch resetDispatch() {
        AuthDispatch d = new AuthDispatch();
        d.id = UUID.randomUUID();
        d.personId = PERSON;
        d.purpose = AuthDispatch.PASSWORD_RESET;
        d.channel = ChannelWire.EMAIL;
        d.subject = "Reset your FSP password";
        d.text = "one-time link: https://portal/#/reset?token=x";
        return d;
    }

    private Person person() {
        Person p = new Person();
        p.id = PERSON;
        p.email = "ops@tenant.example";
        return p;
    }

    @Test
    void noChannelBeanClaimsNoneAndNeverSends() {
        AuthDispatch d = resetDispatch();
        d.channel = ChannelWire.SMS;
        when(dispatches.findPending(any(), any())).thenReturn(List.of(d));
                var dispatcher = dispatcher();
        dispatcher.dispatchOne(d);

        verify(dispatches).claim(eq(d.id), any(), eq("NONE"));
        verify(sms, never()).send(any(), any());
    }

    @Test
    void noProfileContactClaimsNone() {
        AuthDispatch d = resetDispatch();
        when(dispatches.findPending(any(), any())).thenReturn(List.of(d));
        Person p = person();
        p.email = null;
        when(persons.findById(PERSON)).thenReturn(Optional.of(p));

        var dispatcher = dispatcher();
        dispatcher.dispatchOne(d);

        verify(dispatches).claim(eq(d.id), any(), eq("NONE"));
        verify(email, never()).send(any(), any());
    }

    @Test
    void deliveredClaimsViaDeliveryTagAndPurgesSecret() {
        AuthDispatch d = resetDispatch();
        when(dispatches.findPending(any(), any())).thenReturn(List.of(d));
        when(persons.findById(PERSON)).thenReturn(Optional.of(person()));
        when(email.channel()).thenReturn(ChannelWire.EMAIL);
        when(email.deliveryTag()).thenReturn("SMTP");
        when(email.send(any(), any())).thenReturn(new ChannelWire.SendOutcome.Delivered());

        var dispatcher = dispatcher();
        dispatcher.dispatchOne(d);

        verify(dispatches).claim(eq(d.id), any(Instant.class), eq("SMTP"));
        // the send must have received the secret-bearing text exactly as parked
        ArgumentCaptor<ChannelWire.Pointer> ptr = ArgumentCaptor.forClass(ChannelWire.Pointer.class);
        verify(email).send(any(ChannelWire.Target.class), ptr.capture());
        assertTrue(ptr.getValue().body().contains("one-time link"));
        // delivery evidence: audited as DELIVERED on the dispatch, from a hash — never the text
        ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
        verify(audit).hashState(state.capture());
        assertTrue(state.getValue().toString().contains("via=SMTP"));
        assertTrue(!state.getValue().toString().contains("one-time link"), "the message text is never audited");
        verify(audit).record(any(), any(), any(), any(), eq("AUTH_DISPATCH_DELIVERED"), eq("AUTH_DISPATCH"),
            eq(d.id.toString()), any(), any(), eq(false));
    }

    @Test
    void definitiveUnreachableClaimsDead() {
        AuthDispatch d = resetDispatch();
        when(dispatches.findPending(any(), any())).thenReturn(List.of(d));
        when(persons.findById(PERSON)).thenReturn(Optional.of(person()));
        when(email.channel()).thenReturn(ChannelWire.EMAIL);
        when(email.deliveryTag()).thenReturn("SMTP");
        when(email.send(any(), any()))
            .thenReturn(new ChannelWire.SendOutcome.Unreachable(true, "smtp_rejected"));

        var dispatcher = dispatcher();
        dispatcher.dispatchOne(d);

        verify(dispatches).claim(eq(d.id), any(), eq("DEAD"));
        verify(dispatches, never()).bumpAttempts(d.id);
    }

    @Test
    void transientUnreachableBumpsBoundedRetry() {
        AuthDispatch d = resetDispatch();
        when(dispatches.findPending(any(), any())).thenReturn(List.of(d));
        when(persons.findById(PERSON)).thenReturn(Optional.of(person()));
        when(email.channel()).thenReturn(ChannelWire.EMAIL);
        when(email.deliveryTag()).thenReturn("SMTP");
        when(email.send(any(), any()))
            .thenReturn(new ChannelWire.SendOutcome.Unreachable(false, "smtp_transient"));

        var dispatcher = dispatcher();
        dispatcher.dispatchOne(d);

        verify(dispatches).bumpAttempts(d.id);
        // the exhausted-claim statement accompanies every bump; its WHERE clause (attempts >= 3)
        // makes it a no-op while retries remain, and it purges the secret text at the cap
        verify(dispatches).claimExhausted(eq(d.id), any(), eq("EXHAUSTED"));
        verify(dispatches, never()).claim(any(), any(), any());
    }

    @Test
    void skippedClaimsNone() {
        AuthDispatch d = resetDispatch();
        when(dispatches.findPending(any(), any())).thenReturn(List.of(d));
        when(persons.findById(PERSON)).thenReturn(Optional.of(person()));
        when(email.channel()).thenReturn(ChannelWire.EMAIL);
        when(email.send(any(), any()))
            .thenReturn(new ChannelWire.SendOutcome.Skipped("no_plausible_address"));

        var dispatcher = dispatcher();
        dispatcher.dispatchOne(d);

        verify(dispatches).claim(eq(d.id), any(), eq("NONE"));
    }

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
