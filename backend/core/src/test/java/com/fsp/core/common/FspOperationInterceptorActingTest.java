package com.fsp.core.common;

import com.fsp.core.rbac.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Act-as actors (ACTING) on the operation catalog (plans/act-as-tenant-plan.md): the acting person
 * is admitted wherever a PERSON is, decided by person RBAC (which PermissionService caps by the
 * target ceiling), and never by plugin client grants. Pre-fix, act-as reused DELEGATED and was
 * refused on every default {PERSON} operation with ACTOR_TYPE_REJECTED.
 */
class FspOperationInterceptorActingTest {

    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    /** Stand-in controller: one default (person) op and one machine-only op. */
    static class Ops {
        @FspOperation(id = "user.create", component = "identity", permission = "user.manage", title = "Create user")
        public void personOp() { }

        @FspOperation(id = "plugin.event", component = "plugin", permission = "plugin.manage", title = "Machine only",
            actors = {Actor.ActorType.MACHINE})
        public void machineOp() { }
    }

    private final PermissionService permissions = mock(PermissionService.class);
    private final FspOperationInterceptor interceptor = new FspOperationInterceptor(permissions);

    private MockHttpServletRequest request(Actor actor) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/tenants/" + TARGET + "/users");
        req.setAttribute("fsp.actor", actor);
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("tenantId", TARGET.toString()));
        return req;
    }

    private static HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new Ops(), Ops.class.getMethod(method));
    }

    @Test
    void anActingPersonIsAdmittedOnAPersonOperationAndDecidedByPersonRbac() throws Exception {
        Actor acting = new Actor(PERSON, "platform-admin", TARGET, Actor.ActorType.ACTING, null);

        boolean admitted = interceptor.preHandle(request(acting), new MockHttpServletResponse(), handler("personOp"));

        assertThat(admitted).isTrue();
        verify(permissions).require(acting, "user.manage", TARGET);
        verify(permissions, never()).requireClient(anyString(), anyString(), any());
    }

    @Test
    void anActingPersonIsRefusedOnAMachineOnlyOperation() throws Exception {
        Actor acting = new Actor(PERSON, "platform-admin", TARGET, Actor.ActorType.ACTING, null);

        assertThatThrownBy(() ->
            interceptor.preHandle(request(acting), new MockHttpServletResponse(), handler("machineOp")))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("not admitted");
    }

    @Test
    void aPluginDelegatedActorIsStillRefusedOnADefaultPersonOperation() throws Exception {
        // DELEGATED keeps its plugin-only meaning: admitting ACTING must not widen DELEGATED
        Actor plugin = new Actor(PERSON, "platform-admin", TARGET, Actor.ActorType.DELEGATED, "plugin-kyc");

        assertThatThrownBy(() ->
            interceptor.preHandle(request(plugin), new MockHttpServletResponse(), handler("personOp")))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("not admitted");
    }
}
