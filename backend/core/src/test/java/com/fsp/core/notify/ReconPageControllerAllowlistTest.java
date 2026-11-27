package com.fsp.core.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.config.FspProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

/**
 * Red-team regression (round 2 wave 7c): machine client-id allowlist on the recon paging
 * op. A forged MACHINE actor whose client id is not on the allowlist must be rejected
 * BEFORE any notification fan-out (flood defense).
 */
class ReconPageControllerAllowlistTest {

    @Test
    void allowlistedClientMayPage() {
        NotificationService svc = mock(NotificationService.class);
        when(svc.reconPage(Mockito.any(), Mockito.eq("EMI_CUSTODY"), Mockito.eq("CRITICAL"), Mockito.anyString()))
            .thenReturn(2);
        ReconPageController ctl = new ReconPageController(svc, props("recon-worker"));
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Actor actor = new Actor(null, "recon", UUID.randomUUID(), Actor.ActorType.MACHINE, "recon-worker");
        when(req.getAttribute("fsp.actor")).thenReturn(actor);

        var result = ctl.page(req, body());
        Mockito.verify(svc).reconPage(Mockito.any(), Mockito.eq("EMI_CUSTODY"), Mockito.eq("CRITICAL"), Mockito.anyString());
        assertThat(result).containsKey("recipients");
    }

    @Test
    void forgedUnregisteredClientIsRejected_beforeFanOut() {
        NotificationService svc = mock(NotificationService.class);
        ReconPageController ctl = new ReconPageController(svc, props("recon-worker"));
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Actor actor = new Actor(null, "evil", UUID.randomUUID(), Actor.ActorType.MACHINE, "unregistered-client");
        when(req.getAttribute("fsp.actor")).thenReturn(actor);

        assertThatThrownBy(() -> ctl.page(req, body()))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("MACHINE_CLIENT_FORBIDDEN"));
        Mockito.verifyNoInteractions(svc);
    }

    @Test
    void humanPlaneActorIsRejected() {
        NotificationService svc = mock(NotificationService.class);
        ReconPageController ctl = new ReconPageController(svc, props("recon-worker"));
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getAttribute("fsp.actor")).thenReturn(new Actor(UUID.randomUUID(), "joe", UUID.randomUUID()));
        assertThatThrownBy(() -> ctl.page(req, body()))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("MACHINE_CLIENT_FORBIDDEN"));
        Mockito.verifyNoInteractions(svc);
    }

    private static FspProperties props(String allowlist) {
        var props = new FspProperties();
        props.getSecurity().setReconPageAllowedClients(allowlist);
        return props;
    }

    private static ReconPageController.PageBody body() {
        return new ReconPageController.PageBody("EMI_CUSTODY", "CRITICAL", "float drift");
    }
}
