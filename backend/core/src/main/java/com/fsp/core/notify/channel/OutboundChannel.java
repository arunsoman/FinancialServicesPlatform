package com.fsp.core.notify.channel;

/**
 * A non-push outbound transport adapter — EMAIL or SMS (V29; docs/architecture/
 * 12-notifications-and-push.md §12.3–§12.4), each credential-gated exactly like the push
 * publishers: without its credentials a bean is simply absent, and the channel dispatcher
 * records via = NONE without ever leaking data. Adding credentials = flipping config;
 * no code change.
 */
public interface OutboundChannel {

    /** EMAIL or SMS. */
    String channel();

    /** Stable evidence tag written into notifications.*_via on delivery (e.g. SMTP). */
    String deliveryTag();

    /** Send is transport-only: called OUTSIDE any DB transaction (AGENTS §2d). */
    ChannelWire.SendOutcome send(ChannelWire.Target target, ChannelWire.Pointer pointer);
}
