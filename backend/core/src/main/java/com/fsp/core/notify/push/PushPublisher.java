package com.fsp.core.notify.push;

/**
 * A push transport adapter — one per platform (WEBPUSH, APNS, FCM), each credential-gated:
 * without its credentials a publisher bean is simply absent, and the dispatcher records
 * Skipped("channel_unavailable") without ever leaking data. Adding credentials = flipping
 * the config; no code change (docs/architecture/12-notifications-and-push.md).
 */
public interface PushPublisher {

    /** Lowercase platform id of PushSubscription.platform (webpush / apns / fcm). */
    String platform();

    com.fsp.core.notify.push.PushWire.PublishOutcome publish(
        com.fsp.core.notify.push.PushWire.PushTarget target,
        com.fsp.core.notify.push.PushWire.PushPayload payload);
}
