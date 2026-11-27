package com.fsp.core.notify.channel;

import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.notify.AuthDispatch;
import com.fsp.core.notify.AuthDispatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Auth-plane dispatch loop (V30; docs/architecture/12-notifications-and-push.md §12.9).
 * Password-reset / MFA-OTP messages parked on {@code fsp_notifications.auth_dispatches} by
 * the IdP are delivered through the SAME transports as F-12 notifications (V29).
 *
 * Differences from {@link ChannelDispatcher} (both are delivery-only sweeps):
 *  - NO consent gating — an auth message authenticates its recipient, it is never opt-out;
 *  - NO inbox row — nothing is written into the F-12 visibility plane;
 *  - text carries a one-time secret: on claim it is purged in the same statement;
 *  - contact resolved from the person profile at send time, never stored on the row.
 *
 * Claims are short per-row transactions; the provider send runs OUTSIDE any DB transaction
 * (AGENTS §2d). Delivered → claimed via the delivery tag; definitive Unreachable → claimed
 * via DEAD (no prefs to flag — the auth flow itself surfaced the outcome); transient →
 * attempts-1 bump (give up at 3); no bean / no contact / Skipped → claimed via NONE.
 */
@Slf4j
@Component
public class AuthDispatchDispatcher {

    private final AuthDispatchRepository dispatches;
    private final PersonRepository persons;
    private final Map<String, OutboundChannel> channels = new HashMap<>();
    private final TransactionTemplate txWrite;
    private final com.fsp.core.audit.AuditService audit;

    public AuthDispatchDispatcher(AuthDispatchRepository dispatches,
                                  PersonRepository persons,
                                  List<OutboundChannel> channelList,
                                  TransactionTemplate txWrite,
                                  com.fsp.core.audit.AuditService audit) {
        this.audit = audit;
        this.dispatches = dispatches;
        this.persons = persons;
        this.txWrite = txWrite;
        for (OutboundChannel c : channelList) channels.put(c.channel(), c);
    }

    private void runWrite(Runnable write) {
        txWrite.executeWithoutResult(status -> write.run());
    }

    /**
     * Delivery evidence (AGENTS §2.5): the claim and its audit row commit together. Hash-only:
     * purpose, channel, outcome — never the message text, the secret it carried, or the contact.
     */
    private void claimAudited(AuthDispatch d, String via, String outcome, Runnable claim) {
        UUID tenantId = persons.findById(d.personId).map(p -> p.tenantId).orElse(null);
        runWrite(() -> {
            claim.run();
            audit.record(null, null, tenantId, null, "AUTH_DISPATCH_" + outcome, "AUTH_DISPATCH", d.id.toString(),
                null, audit.hashState("person=" + d.personId + ";purpose=" + d.purpose + ";channel=" + d.channel
                    + ";via=" + via + ";attempts=" + d.attempts), false);
        });
    }

    @Scheduled(fixedDelayString = "${fsp.push.dispatchFixedDelay:2000}",
        initialDelayString = "${fsp.push.dispatchInitialDelay:5000}")
    public void sweep() {
        Instant settled = Instant.now().minusSeconds(2);
        for (AuthDispatch d : dispatches.findPending(settled, PageRequest.of(0, 100))) {
            dispatchOne(d);
        }
    }

    void dispatchOne(AuthDispatch d) {
        OutboundChannel channelBean = channels.get(d.channel);
        if (channelBean == null) {
            // channel not credentialled in this deployment — claim ABSENT (§12.2), text purged
            claimAudited(d, "NONE", "NO_CHANNEL", () -> dispatches.claim(d.id, Instant.now(), "NONE"));
            log.info("auth_dispatch_absent person={} purpose={} reason=no_channel_bean",
                d.personId, d.purpose);
            return;
        }
        String contact = contactFor(d);
        if (contact == null) {
            claimAudited(d, "NONE", "NO_CONTACT", () -> dispatches.claim(d.id, Instant.now(), "NONE"));
            log.info("auth_dispatch_absent person={} purpose={} reason=no_profile_contact",
                d.personId, d.purpose);
            return;
        }
        ChannelWire.SendOutcome outcome = channelBean.send(
            new ChannelWire.Target(d.personId, contact),
            new ChannelWire.Pointer(d.purpose, d.subject, d.text, null));
        switch (outcome) {
            case ChannelWire.SendOutcome.Delivered delivered ->
                claimAudited(d, channelBean.deliveryTag(), "DELIVERED",
                    () -> dispatches.claim(d.id, Instant.now(), channelBean.deliveryTag()));
            case ChannelWire.SendOutcome.Unreachable u -> {
                if (u.definitive()) {
                    // the contact itself is gone — nothing to flag (no prefs); claim DEAD
                    claimAudited(d, "DEAD", "DEAD", () -> dispatches.claim(d.id, Instant.now(), "DEAD"));
                    log.info("auth_dispatch_dead person={} purpose={} reason={}",
                        d.personId, d.purpose, u.reason());
                } else {
                    // transient — bounded retry. When the bump crosses the cap in the SAME
                    // short transaction, claim EXHAUSTED so the secret-bearing text is purged
                    // instead of lingering on a row the cursor will never visit again.
                    claimAudited(d, "RETRY", "RETRY", () -> {
                        dispatches.bumpAttempts(d.id);
                        dispatches.claimExhausted(d.id, Instant.now(), "EXHAUSTED");
                    });
                    log.warn("auth_dispatch_retry person={} purpose={} reason={}",
                        d.personId, d.purpose, u.reason());
                }
            }
            case ChannelWire.SendOutcome.Skipped sk -> {
                claimAudited(d, "NONE", "SKIPPED", () -> dispatches.claim(d.id, Instant.now(), "NONE"));
                log.warn("auth_dispatch_skipped person={} purpose={} reason={}",
                    d.personId, d.purpose, sk.reason());
            }
        }
    }

    private String contactFor(AuthDispatch d) {
        return persons.findById(d.personId)
            .map(p -> d.channel.equals(ChannelWire.EMAIL) ? p.email : p.phone)
            .map(c -> c == null || c.isBlank() ? null : c)
            .orElse(null);
    }
}
