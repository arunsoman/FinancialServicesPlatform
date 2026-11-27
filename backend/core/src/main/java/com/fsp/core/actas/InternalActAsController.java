package com.fsp.core.actas;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal hop only (credential-gated by ActorContextFilter; not a catalog operation): the gateway
 * resolves the act-as session of a person→plugin request before it mints the actor assertion
 * (plans/act-as-tenant-plan.md). The person is the one the gateway verified from the token
 * (X-FSP-User-Id); a session that is not live or not theirs is 401 ACT_AS_EXPIRED — the gateway
 * then refuses the call, it never falls back to the person's own tenant.
 */
@Slf4j
@RestController
public class InternalActAsController {

    private final ActAsService sessions;

    public InternalActAsController(ActAsService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/internal/act-as/{sessionId}")
    public Map<String, Object> resolve(HttpServletRequest request, @PathVariable("sessionId") String sessionId) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null || actor.personId() == null || actor.type() != Actor.ActorType.PERSON) {
            throw ApiException.unauthorized("ACTOR_MISSING", "A person is required to resolve an act-as session");
        }
        ActAsService.Live live = sessions.live(sessionId, actor.personId());
        if (live == null) {
            log.warn("internal_act_as_unresolved personId={} session={}", actor.personId(), sessionId);
            throw ApiException.unauthorized("ACT_AS_EXPIRED", "Your session acting as another tenant has ended");
        }
        return Map.of(
            "sessionId", live.id(),
            "personId", live.personId().toString(),
            "homeTenantId", live.homeTenantId().toString(),
            "targetTenantId", live.targetTenantId().toString());
    }
}
