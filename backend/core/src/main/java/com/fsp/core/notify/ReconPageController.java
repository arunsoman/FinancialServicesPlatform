package com.fsp.core.notify;

import com.fsp.core.common.Actor;
import com.fsp.core.common.FspOperation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Recon page dispatch (Stage 6, plan §11): the recon-worker (machine plane, read-only
 * otherwise) posts invariant violations here; pages become notification rows flowing
 * through the V29/V31 channel plane (email/SMS/push — §12.9). The recon worker never
 * writes to core's database directly — this machine-actor op is its only mutator.
 */
@Slf4j
@RestController
@RequestMapping("/v1/recon")
public class ReconPageController {

    private final NotificationService notifications;

    public ReconPageController(NotificationService notifications, com.fsp.core.config.FspProperties props) {
        this.notifications = notifications;
        this.allowedClients = java.util.Arrays.stream(props.getSecurity().getReconPageAllowedClients().split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
    }

    private final java.util.Set<String> allowedClients;

    public record PageBody(String invariant, String severity, String detail) {}

    @PostMapping("/pages")
    @FspOperation(id = "recon.page", component = "platform", permission = "",
        risk = FspOperation.OperationRisk.WRITE, title = "Page on recon invariant violation",
        actors = {Actor.ActorType.MACHINE})
    public Map<String, Object> page(HttpServletRequest request, @RequestBody PageBody body) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        // Red-team P1 fix: only allowlisted machine clients (the recon worker) may fire pages —
        // a forged actor with an unregistered client id is rejected before notification fan-out.
        if (actor.clientId() == null || !allowedClients.contains(actor.clientId())) {
            log.warn("recon_page_client_forbidden clientId={}", actor.clientId());
            throw com.fsp.core.common.ApiException.forbidden("MACHINE_CLIENT_FORBIDDEN",
                "client is not allowlisted for recon paging");
        }
        int recipients = notifications.reconPage(actor.activeTenantId(),
            body.invariant(), body.severity(), body.detail());
        return Map.of("recipients", recipients, "invariant", body.invariant(),
            "severity", body.severity());
    }
}
