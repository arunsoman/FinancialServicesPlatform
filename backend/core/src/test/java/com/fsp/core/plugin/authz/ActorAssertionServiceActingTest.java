package com.fsp.core.plugin.authz;

import com.fsp.core.actas.ActAsService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.RequestCorrelation;
import com.fsp.shared.ActorAssertion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Act-as through a plugin: the gateway's assertion may STATE a session, but core re-verifies it
 * on every on-behalf call and fails closed. Pre-fix the assertion could not carry act-as at all,
 * so a plugin call made while acting ran in the person's HOME tenant.
 */
class ActorAssertionServiceActingTest {

    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID HOME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final ActorAssertion verifier = mock(ActorAssertion.class);
    private final ActAsService actAs = mock(ActAsService.class);
    private final ActorAssertionService service = new ActorAssertionService(verifier, actAs);
    private final Actor machine = new Actor(null, null, TARGET, Actor.ActorType.MACHINE, "plugin-kyc");

    @AfterEach
    void clear() {
        RequestCorrelation.clear();
    }

    private void assertionSays(UUID tenant, String session, UUID home) {
        when(verifier.verify(eq("jwt"), eq("plugin:kyc"), isNull(), isNull())).thenReturn(
            new ActorAssertion.VerifiedActor(PERSON.toString(), tenant.toString(), "platform-admin", "fsp-bff",
                "kyc.case.create", "org.onboard_child", tenant.toString(), "corr", "jti-1", "plugin:kyc",
                session, home == null ? null : home.toString()));
    }

    @Test
    void aLiveSessionOfThePersonMakesTheCallActing() {
        assertionSays(TARGET, "sess-1", HOME);
        when(actAs.live("sess-1", PERSON)).thenReturn(new ActAsService.Live("sess-1", PERSON, HOME, TARGET));

        Actor delegated = service.delegate(machine, "jwt");

        assertThat(delegated.type()).isEqualTo(Actor.ActorType.DELEGATED);
        assertThat(delegated.activeTenantId()).isEqualTo(TARGET);
        assertThat(RequestCorrelation.homeTenantId()).isEqualTo(HOME);
        assertThat(RequestCorrelation.actSessionId()).isEqualTo("sess-1");
    }

    @Test
    void anEndedSessionIsRefused() {
        assertionSays(TARGET, "sess-1", HOME);
        when(actAs.live(any(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.delegate(machine, "jwt"))
            .isInstanceOf(ApiException.class).hasMessageContaining("acting");
        assertThat(RequestCorrelation.actSessionId()).isNull();
    }

    @Test
    void aSessionForAnotherTenantIsRefused() {
        UUID elsewhere = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        assertionSays(elsewhere, "sess-1", HOME);
        when(actAs.live("sess-1", PERSON)).thenReturn(new ActAsService.Live("sess-1", PERSON, HOME, TARGET));

        assertThatThrownBy(() -> service.delegate(machine, "jwt")).isInstanceOf(ApiException.class);
    }

    @Test
    void anOrdinaryAssertionIsNotActing() {
        assertionSays(HOME, null, null);

        Actor delegated = service.delegate(machine, "jwt");

        assertThat(delegated.activeTenantId()).isEqualTo(HOME);
        assertThat(RequestCorrelation.actSessionId()).isNull();
    }
}
