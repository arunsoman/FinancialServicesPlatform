package com.fsp.bff;

import com.fsp.bff.BffDtos.SessionUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The ops console (Grafana /ops/) admits only a live session of platform staff — never a token. */
class BffOpsControllerTest {

    private final SessionTokens tokens = mock(SessionTokens.class);
    private final BffOpsController controller = new BffOpsController(tokens);

    private MockHttpServletRequest request(SessionUser user, Instant end) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/bff/ops/auth");
        MockHttpSession s = new MockHttpSession();
        if (user != null) s.setAttribute(BffSessionController.SESSION_USER, user);
        r.setSession(s);
        when(tokens.absoluteEndInstant(any())).thenReturn(end);
        return r;
    }

    private static SessionUser user(String tenantType, String username) {
        return new SessionUser("u1", username, "Platform Admin", "t1", "FSP", tenantType, null, "ACTIVE", false, List.of());
    }

    @Test
    void platformStaffWithALiveSessionIsNamedToNginx() {
        var res = controller.auth(request(user("PLATFORM", "platform-admin"), Instant.now().plusSeconds(600)));
        assertEquals(204, res.getStatusCode().value());
        assertEquals("platform-admin", res.getHeaders().getFirst("X-FSP-Ops-User"));
    }

    @Test
    void noSessionOrAnEndedOneIs401() {
        assertEquals(401, controller.auth(request(null, Instant.now().plusSeconds(600))).getStatusCode().value());
        assertEquals(401, controller.auth(request(user("PLATFORM", "platform-admin"), Instant.now().minusSeconds(1)))
            .getStatusCode().value());
    }

    @Test
    void tenantUsersAreRefusedAndNameNoOne() {
        var res = controller.auth(request(user("PSP", "psp-admin"), Instant.now().plusSeconds(600)));
        assertEquals(403, res.getStatusCode().value());
        assertNull(res.getHeaders().getFirst("X-FSP-Ops-User"));
    }

    @Test
    void anOddUsernameNeverBecomesAGrafanaLogin() {
        var res = controller.auth(request(user("PLATFORM", "admin\r\nX-Evil: 1"), Instant.now().plusSeconds(600)));
        assertEquals(403, res.getStatusCode().value());
    }
}
