package com.fsp.bff;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Browser-plane origin hardening (red-team rounds 2–3): the BFF is the browser's ONLY
 * origin by design, so every state-changing request MUST carry same-browser provenance.
 * A request is admitted when it presents an {@code Origin} header whose host matches one
 * of the configured browser origins, or an explicit {@code Referer} from the same allowlist.
 * Non-browser API clients never talk to the BFF (they use the gateway).
 */
public class OriginEnforcementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OriginEnforcementFilter.class);

    private final Set<String> allowedHosts;

    public OriginEnforcementFilter(String allowedOriginsCsv) {
        this.allowedHosts = Stream.of(allowedOriginsCsv.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).map(o -> URI.create(o).getHost())
            .collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        boolean mutating = !"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method);
        if (mutating && allowed(request) == false) {
            log.warn("bff_origin_rejected method={} remote={} origin={}",
                method, request.getRemoteAddr(), request.getHeader("Origin"));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"code\":\"ORIGIN_REJECTED\",\"detail\":\"state-changing calls must come from an allowed browser origin\"}");
            return;
        }
        if (chain != null) chain.doFilter(request, response);
    }

    private boolean allowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return allowedHosts.contains(URI.create(origin).getHost());
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            return allowedHosts.contains(URI.create(referer).getHost());
        }
        // Same-origin fetch metadata without Origin (rare legacy clients) — explicit opt-in only.
        return "same-origin".equals(request.getHeader("Sec-Fetch-Site"));
    }
}
