package com.fsp.core.plugin.authz;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.RequestCorrelation;
import com.fsp.core.actas.ActAsService;
import com.fsp.shared.ActorAssertion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Path D (plan §4.4): a plugin's MACHINE call that carries the gateway's actor assertion is
 * resolved to a DELEGATED actor — the person from the assertion, the client from the
 * plugin's own (DPoP-bound) token. The FspOperationInterceptor then requires BOTH the
 * person's permission AND the client's grant.
 *
 * The assertion must be addressed to THIS client's plugin (aud plugin:&lt;code&gt;, client id
 * plugin-&lt;code&gt;), so a plugin can never replay another plugin's assertion.
 *
 * Act-as through a plugin (plans/act-as-tenant-plan.md): when the assertion states an act-as
 * session, core re-verifies it here — live, this person's, this target — and records the home
 * tenant + session for the request, so PermissionService caps the person by the target's
 * ceiling and every audit row names the session. The gateway's word is never enough.
 */
@Slf4j
@Service
public class ActorAssertionService implements com.fsp.core.common.ActorContextFilter.Delegation {

    private static final String CLIENT_PREFIX = "plugin-";

    private final ActorAssertion assertion;
    private final ActAsService actAs;

    public ActorAssertionService(ActorAssertion assertion, ActAsService actAs) {
        this.assertion = assertion;
        this.actAs = actAs;
    }

    @Override
    public Actor delegate(Actor machine, String assertionJwt) {
        String clientId = machine.clientId();
        if (clientId == null || !clientId.startsWith(CLIENT_PREFIX)) {
            log.warn("assertion_client_not_plugin clientId={}", clientId);
            throw ApiException.forbidden("ACTOR_TYPE_REJECTED", "only plugin clients may act on behalf of a person");
        }
        String audience = "plugin:" + clientId.substring(CLIENT_PREFIX.length());
        try {
            ActorAssertion.VerifiedActor va = assertion.verify(assertionJwt, audience, null, null);
            UUID personId = UUID.fromString(va.personId());
            UUID tenantId = UUID.fromString(va.tenantId());
            if (va.acting()) {
                ActAsService.Live live = actAs.live(va.actSessionId(), personId);
                if (live == null || !live.targetTenantId().equals(tenantId)
                    || !String.valueOf(live.homeTenantId()).equals(va.homeTenantId())) {
                    log.warn("assertion_act_as_rejected client={} person={} session={}",
                        clientId, personId, va.actSessionId());
                    throw ApiException.unauthorized("ACT_AS_EXPIRED", "Your session acting as another tenant has ended");
                }
                RequestCorrelation.setHomeTenantId(live.homeTenantId());
                RequestCorrelation.setActSessionId(live.id());
            }
            Actor delegated = new Actor(personId, va.username(), tenantId, Actor.ActorType.DELEGATED, clientId);
            log.debug("assertion_delegated client={} person={} op={} jti={}",
                clientId, va.personId(), va.operationId(), va.jti());
            return delegated;
        } catch (ActorAssertion.Rejected e) {
            log.warn("assertion_rejected client={} code={}", clientId, e.code());
            if ("ASSERTION_OVER_BUDGET".equals(e.code()) || "ASSERTION_REPLAY".equals(e.code())) {
                throw ApiException.forbidden(e.code(), "actor assertion already used up");
            }
            throw ApiException.unauthorized(e.code(), "actor assertion rejected");
        } catch (IllegalArgumentException e) {
            log.warn("assertion_subject_invalid client={}", clientId);
            throw ApiException.unauthorized("ASSERTION_INVALID", "actor assertion subject invalid");
        }
    }
}
