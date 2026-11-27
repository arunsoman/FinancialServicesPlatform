package com.fsp.core.notify.channel;

import java.util.UUID;

/**
 * Email/SMS channel wire types + outcome algebra (V29; docs/architecture/
 * 12-notifications-and-push.md §12.3–§12.4). Same shape discipline as {@code PushWire}:
 * one pointer payload, one closed outcome algebra, transport adapters behind
 * {@link OutboundChannel}.
 */
public final class ChannelWire {

    private ChannelWire() {}

    /** The outbound channel a publisher owns. */
    public static final String EMAIL = "EMAIL";
    public static final String SMS = "SMS";

    /** One recipient target resolved from the person profile (never self-registered). */
    public record Target(UUID personId, String contact) {}

    /** The pointer to deliver. Data-minimized BY DESIGN (AGENTS §2c): generic copy +
     *  portal-relative link. Email appends the link (portal re-authenticates); SMS omits
     *  it entirely and caps length — plaintext at the telco is the strictest budget. */
    public record Pointer(String type, String title, String body, String link) {}

    /** Result of ONE send attempt to ONE target — closed algebra, exhaustive switch. */
    public sealed interface SendOutcome {
        /** The provider accepted the message. */
        record Delivered() implements SendOutcome {}
        /** Provider could not accept: definitive = contact itself is gone (flag the pref
         *  dead), transient = provider hiccup (bounded retry). */
        record Unreachable(boolean definitive, String reason) implements SendOutcome {}
        /** Not attempted (channel credentials missing / malformed target). */
        record Skipped(String reason) implements SendOutcome {}
    }
}
