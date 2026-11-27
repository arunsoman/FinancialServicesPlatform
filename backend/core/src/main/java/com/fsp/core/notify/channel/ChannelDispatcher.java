package com.fsp.core.notify.channel;

import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.notify.Notification;
import com.fsp.core.notify.NotificationChannelPref;
import com.fsp.core.notify.NotificationChannelPrefRepository;
import com.fsp.core.notify.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Email/SMS dispatch loop (V29; docs/architecture/12-notifications-and-push.md §12.3–
 * §12.4). Sweeps the two per-channel cursors on {@code notifications} (the rows whose
 * email/sms claim is NULL), resolves the target from the PERSON PROFILE (never
 * self-registered), consults the person's opt-in pref, and hands the send to the
 * credential-gated {@link OutboundChannel} bean when one exists.
 *
 * Claims happen in short per-row transactions; the provider send runs OUTSIDE any DB
 * transaction (AGENTS §2d). Outcomes per channel, mirroring the push dispatcher:
 * Delivered → claimed via the channel's delivery tag; definitive Unreachable → channel
 * pref flagged dead + claimed via NONE; transient → attempts++ (bounded, give up at 3);
 * Skipped / no pref / no contact / no channel bean → claimed via NONE.
 *
 * The in-app inbox (and its push pointer) is unaffected either way: a channel can only
 * add evidence, never delay another channel's claim.
 */
@Slf4j
@Component
public class ChannelDispatcher {

    private final NotificationRepository notifications;
    private final PersonRepository persons;
    private final NotificationChannelPrefRepository prefs;
    private final Map<String, OutboundChannel> channels = new HashMap<>();
    private final TransactionTemplate txWrite;

    public ChannelDispatcher(NotificationRepository notifications,
                             PersonRepository persons,
                             NotificationChannelPrefRepository prefs,
                             List<OutboundChannel> channelList,
                             TransactionTemplate txWrite) {
        this.notifications = notifications;
        this.persons = persons;
        this.prefs = prefs;
        this.txWrite = txWrite;
        for (OutboundChannel c : channelList) channels.put(c.channel(), c);
    }

    private void runWrite(Runnable write) {
        txWrite.executeWithoutResult(status -> write.run());
    }

    @Scheduled(fixedDelayString = "${fsp.push.dispatchFixedDelay:2000}",
        initialDelayString = "${fsp.push.dispatchInitialDelay:5000}")
    public void sweep() {
        Instant settled = Instant.now().minusSeconds(2);
        sweepChannel(ChannelWire.EMAIL, notifications.findEmailPending(settled, PageRequest.of(0, 100)));
        sweepChannel(ChannelWire.SMS, notifications.findSmsPending(settled, PageRequest.of(0, 100)));
    }

    void sweepChannel(String channel, List<Notification> pending) {
        OutboundChannel channelBean = channels.get(channel);
        for (Notification n : pending) {
            dispatchOne(channel, channelBean, n);
        }
    }

    private void dispatchOne(String channel, OutboundChannel channelBean, Notification n) {
        Optional<NotificationChannelPref> pref = prefs.findByPersonIdAndChannel(n.personId, channel);
        if (pref.isEmpty() || !pref.get().enabled || channelBean == null) {
            claim(n, channel, "NONE"); // consent off / channel not credentialled — ABSENT (§12.2)
            return;
        }
        String contact = resolveContact(n, channel);
        if (contact == null) {
            claim(n, channel, "NONE"); // no profile contact on this channel
            return;
        }
        ChannelWire.SendOutcome outcome = channelBean.send(
            new ChannelWire.Target(n.personId, contact),
            new ChannelWire.Pointer(n.type, n.title, n.body, n.link));
        switch (outcome) {
            case ChannelWire.SendOutcome.Delivered d -> claim(n, channel, channelBean.deliveryTag());
            case ChannelWire.SendOutcome.Unreachable u -> {
                if (u.definitive()) {
                    // the CONTACT is gone (invalid mailbox / blocked number) — flag dead,
                    // keep the consent visible; the person re-enables after fixing the profile
                    runWrite(() -> prefs.flagDead(n.personId, channel, Instant.now()));
                    claim(n, channel, "NONE");
                    log.info("channel_contact_dead person={} channel={} reason={}",
                        n.personId, channel, u.reason());
                } else {
                    runWrite(() -> bump(n, channel)); // transient — bounded retry
                }
            }
            case ChannelWire.SendOutcome.Skipped sk -> {
                // never attempted (malformed target) — claim NONE, evidence of no send
                claim(n, channel, "NONE");
                log.warn("channel_skipped person={} channel={} reason={}", n.personId, channel, sk.reason());
            }
        }
    }

    private String resolveContact(Notification n, String channel) {
        Optional<Person> person = persons.findById(n.personId);
        if (person.isEmpty()) return null;
        String contact = channel.equals(ChannelWire.EMAIL) ? person.get().email : person.get().phone;
        return contact == null || contact.isBlank() ? null : contact;
    }

    private void bump(Notification n, String channel) {
        if (channel.equals(ChannelWire.EMAIL)) {
            notifications.bumpEmailAttempts(n.id);
        } else {
            notifications.bumpSmsAttempts(n.id);
        }
        log.debug("channel_retry person={} channel={} notification={}", n.personId, channel, n.id);
    }

    private void claim(Notification n, String channel, String via) {
        Instant at = Instant.now();
        if (channel.equals(ChannelWire.EMAIL)) {
            runWrite(() -> notifications.claimEmailed(n.id, at, via));
        } else {
            runWrite(() -> notifications.claimSmsSent(n.id, at, via));
        }
    }
}
