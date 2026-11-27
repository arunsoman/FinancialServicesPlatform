package com.fsp.core.actas;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.RequestCorrelation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Act-as filter (plans/act-as-tenant-plan.md §3.3). Runs after {@link
 * com.fsp.core.common.ActorContextFilter}: when the request carries {@code X-Act-As: <session
 * id>}, the session must belong to the authenticated person and be live; the principal then
 * becomes the acting principal (same person, the tenant acted as). A stale or foreign session is
 * a 401 {@code ACT_AS_EXPIRED} - never a silent fall-back to the person's own tenant.
 *
 * Not a Spring bean on purpose: built by {@link com.fsp.core.config.CoreConfig}, so it runs
 * only inside the filter chain, after the identity filters.
 */
public class ActAsFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Act-As";

    private final ActAsService sessions;

    public ActAsFilter(ActAsService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        // The act-as endpoints themselves are about the person, so never apply a live session to them.
        boolean own = request.getRequestURI().startsWith("/v1/act-as");
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (id == null || id.isBlank() || own || actor == null) {
            chain.doFilter(request, response);
            return;
        }
        ActAsService.Live live = sessions.live(id.trim(), actor.personId());
        if (live == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"ACT_AS_EXPIRED\",\"title\":\"Unauthorized\","
                    + "\"detail\":\"Your session acting as another tenant has ended\"}");
            return;
        }
        // Remember the home tenant before switching context.
        RequestCorrelation.setHomeTenantId(live.homeTenantId());
        // every audit row written under this request names the session (plan C6)
        RequestCorrelation.setActSessionId(live.id());
        Actor acting = new Actor(live.personId(), actor.username(), live.targetTenantId(),
            Actor.ActorType.ACTING, null);
        request.setAttribute("fsp.actor", acting);
        RequestCorrelation.setActor(acting);
        chain.doFilter(request, response);
    }
}
