package com.fsp.core.notify.push;

import com.fsp.core.notify.Notification;
import com.fsp.core.notify.NotificationRepository;
import com.fsp.core.notify.PushSubscription;
import com.fsp.core.notify.PushSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Push spine dispatch loop (F-12). Scans {@code notifications} where {@code pushed_at IS
 * NULL} (the table IS the queue — crash-safe, catches up after downtime), resolves each
 * person's ACTIVE device registrations, and hands one minimal {@link PushWire.PushPayload}
 * per device to the matching credential-gated {@link PushPublisher}. Outcomes:
 * Delivered → row claimed (push_via = channel); definitive Unreachable → subscription
 * retired (dead device); non-definitive Unreachable → attempts++ (bounded retry, give up
 * at 3); Skipped / no channel / no subscriptions → row claimed with push_via = NONE.
 * The in-app inbox is the source of truth — a push is a best-effort pointer, never money.
 */
@Slf4j
@Component
public class PushDispatcher {

    private final NotificationRepository notifications;
    private final PushSubscriptionRepository subscriptions;
    private final Map<String, PushPublisher> publishers = new HashMap<>();

    private final TransactionTemplate txWrite;

    public PushDispatcher(NotificationRepository notifications,
                          PushSubscriptionRepository subscriptions,
                          List<PushPublisher> publisherList,
                          TransactionTemplate txWrite) {
        this.notifications = notifications;
        this.subscriptions = subscriptions;
        this.txWrite = txWrite;
        for (PushPublisher p : publisherList) publishers.put(p.platform(), p);
    }

    /** Every @Modifying update below needs an active transaction (Spring 7 fails fast otherwise).
     *  Short per-row transactions only — device sends happen OUTSIDE any DB transaction. */
    private void runWrite(Runnable write) {
        txWrite.executeWithoutResult(status -> write.run());
    }

    /** One sweep, serial. Single-instance in Phase-0; multi-replica hardening = claim-before-send. */
    @Scheduled(fixedDelayString = "${fsp.push.dispatchFixedDelay:2000}", initialDelayString = "${fsp.push.dispatchInitialDelay:5000}")
    public void sweep() {
        List<Notification> pending = notifications.findPushPending(
            Instant.now().minusSeconds(2), PageRequest.of(0, 100));
        for (Notification n : pending) {
            dispatchOne(n, subscriptions.findByPersonIdAndStatusOrderByCreatedAtAsc(n.personId, "ACTIVE"));
        }
        if (!pending.isEmpty()) log.debug("push_swept pending={}", pending.size());
    }

    void dispatchOne(Notification n, List<PushSubscription> targets) {
        if (n.pushAttempts >= 3) { // safety net: sweep query already filters these
            claim(n, "NONE");
            log.info("push_gave_up notification={} attempts={}", n.id, n.pushAttempts);
            return;
        }
        PushWire.PushPayload payload = new PushWire.PushPayload(
            n.type, n.title, n.body, String.valueOf(n.tenantId), n.link);
        String deliveredVia = null;
        boolean credentialledChannelAlive = false;
        for (PushSubscription s : targets) {
            PushPublisher publisher = publishers.get(s.platform.toLowerCase());
            if (publisher == null) continue; // channel without credentials — no dispatch possible

            PushWire.PushTarget target = new PushWire.PushTarget(
                s.id, s.platform, s.endpoint, s.token, s.p256dh, s.auth);
            PushWire.PublishOutcome outcome = publisher.publish(target, payload);
            switch (outcome) {
                case PushWire.PublishOutcome.Delivered d -> {
                    if (deliveredVia == null) deliveredVia = s.platform;
                    runWrite(() -> subscriptions.touch(s.id, Instant.now()));
                }
                case PushWire.PublishOutcome.Unreachable u -> {
                    if (u.definitive()) {
                        runWrite(() -> subscriptions.retire(s.id, Instant.now()));
                        log.warn("push_subscription_retired platform={} person={}" +
                            " reason={}", s.platform, s.personId, u.reason());
                    } else {
                        credentialledChannelAlive = true; // transient — worth a bounded retry
                        log.warn("push_unreachable platform={} person={} reason={}",
                            s.platform, s.personId, u.reason());
                    }
                }
                case PushWire.PublishOutcome.Skipped sk ->
                    log.debug("push_skipped platform={} reason={}", s.platform, sk.reason());
            }
        }

        if (deliveredVia != null) {
            claim(n, deliveredVia);
        } else if (credentialledChannelAlive) {
            runWrite(() -> notifications.bumpAttempts(n.id));
        } else {
            claim(n, "NONE"); // nothing subscribed, or only credential-less channels
        }
    }

    private void claim(Notification n, String via) {
        runWrite(() -> notifications.claimPushed(n.id, Instant.now(), via));
    }
}
