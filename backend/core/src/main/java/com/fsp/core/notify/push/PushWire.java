package com.fsp.core.notify.push;

import java.util.UUID;

/**
 * Push spine wire types + outcome algebra (F-12; docs/architecture/12-notifications-and-push.md).
 * One payload shape for every platform; consumers are transport adapters behind
 * {@link PushPublisher}, selected per {@link PushTarget} platform by the dispatch loop.
 */
public final class PushWire {

    private PushWire() {}

    /** One registered destination: platform + device coordinates (encrypted keys for WEBPUSH). */
    public record PushTarget(UUID subscriptionId, String platform, String endpoint,
                             String token, String key, String auth) {}

    /** The pointer to deliver. Data-minimized BY DESIGN (FAPI/AGENTS §2c): no names, no
     *  amounts, no account data — opaque type + link; title/body are the FSP's generic words. */
    public record PushPayload(String type, String title, String body, String tenantId, String link) {}

    /** Result of ONE publish attempt to ONE target — closed algebra, exhaustive switch. */
    public sealed interface PublishOutcome {
        /** The provider accepted the message. */
        record Delivered() implements PublishOutcome {}
        /** Provider could not accept: definitive = destination gone (retire the subscription),
         *  otherwise leave it healthy — providers hiccup. */
        record Unreachable(boolean definitive, String reason) implements PublishOutcome {}
        /** Not attempted (channel disabled / credentials missing / malformed target). */
        record Skipped(String reason) implements PublishOutcome {}
    }
}
