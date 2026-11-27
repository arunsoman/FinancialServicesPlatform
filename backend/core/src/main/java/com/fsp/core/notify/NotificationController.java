package com.fsp.core.notify;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import com.fsp.core.config.FspProperties;
import com.fsp.core.rbac.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F-12 surface: the person's notification inbox + push-device registration. All operations
 * require {@code notification.view} (it governs reads AND device registration — a device is
 * a lens on your inbox). Person-plane identities only: {@code actor.personId()} is the home
 * person (never re-based by act-as), so a DELEGATED/MACHINE actor is refused.
 */
@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    public record PushSubscribeRequest(String platform, String endpoint, String token,
                                       String p256dh, String auth, String userAgent) {}

    public record PushSubscriptionView(UUID id, String platform, String status, String createdAt) {}

    private final NotificationService service;
    private final PermissionService permissions;
    private final FspProperties props;

    public NotificationController(NotificationService service, PermissionService permissions,
                                  FspProperties props) {
        this.service = service;
        this.permissions = permissions;
        this.props = props;
    }

    @FspOperation(id = "notification.list", permission = "notification.view", component = "notify",
        title = "Read the notification inbox")
    @GetMapping
    public NotificationService.NotificationPage list(HttpServletRequest request,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "unread", required = false) Boolean unread,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "sort", required = false) String sort) {
        Actor actor = actor(request);
        return service.listPage(actor.personId(), page, size, q, unread, state, sort);
    }

    /** Cheap poll target for the badge (no rows over the wire). {@code pending} = not acted
     *  upon and not snoozed — what the bell shows; {@code unread} kept for older clients. */
    @FspOperation(id = "notification.unread-count", permission = "notification.view", component = "notify",
        title = "Read the pending notification count")
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> unread(HttpServletRequest request) {
        Actor actor = actor(request);
        return ResponseEntity.ok(Map.of(
            "unread", service.unreadCount(actor.personId()),
            "pending", service.pendingCount(actor.personId())));
    }

    public record SnoozeRequest(String until) {}

    /** Park a notification until {@code until} (ISO-8601 instant); it returns to the pending inbox then. */
    @FspOperation(id = "notification.snooze", permission = "notification.view", component = "notify",
        risk = FspOperation.OperationRisk.WRITE, title = "Snooze one of your notifications")
    @PostMapping("/{id}/snooze")
    public NotificationService.NotificationView snooze(@PathVariable UUID id, @RequestBody SnoozeRequest body,
                                                       HttpServletRequest request) {
        Actor actor = actor(request);
        java.time.Instant until;
        try {
            until = body == null || body.until() == null ? null : java.time.Instant.parse(body.until());
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("SNOOZE_UNTIL_INVALID", "until must be an ISO-8601 instant");
        }
        return service.snooze(actor.personId(), id, until);
    }

    @FspOperation(id = "notification.wake", permission = "notification.view", component = "notify",
        risk = FspOperation.OperationRisk.WRITE, title = "Bring a snoozed notification back now")
    @PostMapping("/{id}/wake")
    public NotificationService.NotificationView wake(@PathVariable UUID id, HttpServletRequest request) {
        Actor actor = actor(request);
        return service.wake(actor.personId(), id);
    }

    @FspOperation(id = "notification.dismiss", permission = "notification.view", component = "notify",
        risk = FspOperation.OperationRisk.WRITE, title = "Dismiss an informational notification")
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(@PathVariable UUID id, HttpServletRequest request) {
        Actor actor = actor(request);
        service.dismiss(actor.personId(), id);
        return ResponseEntity.noContent().build();
    }

    @FspOperation(id = "notification.read", permission = "notification.view", component = "notify",
        risk = FspOperation.OperationRisk.WRITE, title = "Mark one notification read")
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable UUID id, HttpServletRequest request) {
        Actor actor = actor(request);
        service.markRead(actor.personId(), id);
        return ResponseEntity.noContent().build();
    }

    @FspOperation(id = "notification.read-all", permission = "notification.view", component = "notify",
        risk = FspOperation.OperationRisk.WRITE, title = "Mark all notifications read")
    @PostMapping("/read-all")
    public ResponseEntity<Void> markRead(HttpServletRequest request) {
        Actor actor = actor(request);
        service.markAllRead(actor.personId());
        return ResponseEntity.noContent().build();
    }

    /** Public VAPID key for the SPA service worker (only when the webpush channel is on). */
    @FspOperation(id = "notification.push.config", permission = "notification.view", component = "notify",
        title = "Read the push channel configuration")
    @GetMapping("/push/config")
    public ResponseEntity<Map<String, Object>> pushConfig(HttpServletRequest request) {
        actor(request);
        var wp = props.getPush();
        if (!wp.isWebpushEnabled()) {
            return ResponseEntity.ok(Map.of("webpushEnabled", false));
        }
        return ResponseEntity.ok(Map.of(
            "webpushEnabled", true,
            "vapidPublicKey", wp.getWebpushPublicKey()));
    }

    @FspOperation(id = "notification.push.subscribe", permission = "notification.view", component = "notify",
        risk = FspOperation.OperationRisk.WRITE, title = "Register a push device for your identity")
    @PostMapping("/push/subscriptions")
    public ResponseEntity<PushSubscriptionView> subscribe(@RequestBody PushSubscribeRequest body,
                                                          HttpServletRequest request) {
        Actor actor = actor(request);
        PushSubscription row = service.subscribe(
            actor.personId(), body.platform(), body.endpoint(), body.token(),
            body.p256dh(), body.auth(), body.userAgent());
        return ResponseEntity.ok(new PushSubscriptionView(row.id, row.platform, row.status,
            String.valueOf(row.createdAt)));
    }

    /** The browser forgot the device → retire the row (definitive). Endpoint is URL-encoded in the path. */
    @FspOperation(id = "notification.push.unsubscribe", permission = "notification.view", component = "notify",
        risk = FspOperation.OperationRisk.WRITE, title = "Retire a push device for your identity")
    @DeleteMapping("/push/subscriptions/{endpoint}")
    public ResponseEntity<Void> unsubscribe(@PathVariable String endpoint, HttpServletRequest request) {
        Actor actor = actor(request);
        service.unsubscribe(URLDecoder.decode(endpoint, StandardCharsets.UTF_8), actor.personId());
        return ResponseEntity.noContent().build();
    }

    /** Outbound-channel consent state — never the raw address (data minimization, §12.5). */
    @FspOperation(id = "notification.channels.read", permission = "notification.view", component = "notify",
        title = "Read your email/SMS notification channels")
    @GetMapping("/channels")
    public ResponseEntity<Map<String, Object>> channels(HttpServletRequest request) {
        Actor actor = actor(request);
        return ResponseEntity.ok(Map.of("channels", service.channelState(actor.personId())));
    }

    public record ChannelPrefRequest(Boolean email, Boolean sms) {}

    /** Opt in/out per channel. Consent lives at the person plane; profile contact must
     *  exist to enable (400 CHANNEL_NO_CONTACT otherwise). Enabling clears a bounce flag. */
    @FspOperation(id = "notification.channels.update", permission = "notification.view", component = "notify",
        risk = FspOperation.OperationRisk.WRITE, title = "Opt in/out of email/SMS notification channels")
    @PutMapping("/channels")
    public ResponseEntity<Map<String, Object>> setChannels(@RequestBody ChannelPrefRequest body,
                                                           HttpServletRequest request) {
        Actor actor = actor(request);
        if (body.email() == null && body.sms() == null) {
            throw ApiException.badRequest("CHANNEL_NOOP", "provide email and/or sms toggles");
        }
        List<NotificationService.ChannelState> states =
            service.setChannels(actor.personId(), body.email(), body.sms());
        return ResponseEntity.ok(Map.of("channels", states));
    }

    /** Person plane only + notification.view gate, scoped to the actor's active tenant. */
    private Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null || actor.type() != Actor.ActorType.PERSON) {
            throw ApiException.unauthorized("ACTOR_MISSING", "a person identity is required");
        }
        permissions.require(actor, "notification.view", actor.activeTenantId());
        return actor;
    }
}
