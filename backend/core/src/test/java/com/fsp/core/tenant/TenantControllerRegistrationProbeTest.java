package com.fsp.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fsp.core.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

/**
 * Registration probe for {@link TenantController}.
 *
 * Commit a5e2e0e dropped the class-level {@code @RestController} stereotype while keeping the
 * per-method {@code @RequestMapping}s. Spring only scans a class for handler methods when the
 * class carries {@code @Controller} (which {@code @RestController} meta-annotates) or a
 * type-level {@code @RequestMapping} — method-level mappings alone never register. As committed,
 * every {@code /v1/tenants/**} route on this controller silently returned 404 (Scenes 2.4,
 * 4.2/4.4, 6.3 and 7 of the onboarding script cannot pass against such a build).
 *
 * This probe pins the failure mode so a future stereotype slip fails a unit test instead of an
 * E2E scene: standalone MockMvc scans exactly the handler classes handed to it, so
 * <ul>
 *   <li>WITHOUT the stereotype → no handler method is found → the request falls off the
 *       mapping (plain 404, no registered handler, no exception from the controller)</li>
 *   <li>WITH the stereotype → the route maps and the controller EXECUTES on the bare MockMvc
 *       request to its first real guard ({@code ACTOR_MISSING}, no actor attribute yet). That
 *       guard exception surfacing — or any mapped non-404 status — proves registration.</li>
 * </ul>
 * A mocked {@link TenantService} keeps this a pure web-annotation test — no DB, no ledger, no
 * running stack.
 */
class TenantControllerRegistrationProbeTest {

    /** What one request tells us: the route mapped, and (optionally) the controller's own guard threw. */
    private record Probe(boolean mapped, int status, Throwable controllerError) { }

    private MockMvc mockMvcFor(TenantController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** Runs a request to completion; a handler-thrown ServletException surfaces the controller's guard. */
    private Probe probe(MockMvc mvc, MockHttpServletRequestBuilder request) {
        try {
            var result = mvc.perform(request).andReturn();
            return new Probe(result.getHandler() != null, result.getResponse().getStatus(), null);
        } catch (jakarta.servlet.ServletException e) {
            // The route mapped AND the controller body ran to a guard exception (standalone MockMvc
            // rethrows unhandled handler exceptions) — registration proven beyond doubt.
            return new Probe(true, -1, e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("probe request failed outside the controller: " + e, e);
        }
    }

    private void assertRegistered(String route, Probe p) {
        assertThat(p.mapped())
            .as("%s must map to a TenantController handler method — a 404 on this route with the "
                + "controller compiled means the class-level @RestController stereotype is missing "
                + "(see a5e2e0e)", route)
            .isTrue();
        if (p.controllerError() != null) {
            assertThat(p.controllerError())
                .as("%s executed inside the controller stack; its guard error must be the app's "
                    + "ApiException, not a framework failure", route)
                .isInstanceOf(ApiException.class);
        } else {
            assertThat(p.status())
                .as("%s resolves to a mapped non-404 status; got %d", route, p.status())
                .isNotEqualTo(404);
        }
    }

    @Test
    void tenantDetailRouteIsRegistered_andNeverFallsOffTheMapping() {
        MockMvc mvc = mockMvcFor(new TenantController(mock(TenantService.class)));
        var p = probe(mvc,
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/v1/tenants/{tenantId}", UUID.randomUUID()));
        assertRegistered("GET /v1/tenants/{tenantId}", p);
    }

    @Test
    void allOtherTenantRoutesStayRegisteredAgainstTheSameFailureMode() {
        MockMvc mvc = mockMvcFor(new TenantController(mock(TenantService.class)));
        assertRegistered("POST /v1/tenants", probe(mvc,
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/tenants")));
        assertRegistered("GET /v1/tenants/{tenantId}/children", probe(mvc,
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/v1/tenants/{tenantId}/children", UUID.randomUUID())));
    }
}
