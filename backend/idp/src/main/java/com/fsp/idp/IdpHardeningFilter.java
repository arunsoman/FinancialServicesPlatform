package com.fsp.idp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Baseline response hardening (red-team round 2, wave 10a): the IdP renders the hosted
 * sign-in / MFA / password surfaces — they get a strict CSP and referrer lockdown. JSON
 * endpoints are unaffected functionally.
 */
public class IdpHardeningFilter extends OncePerRequestFilter {

    static final String CSP_HEADER = "Content-Security-Policy";

    /** scheme://host[:port] — nothing that could smuggle another CSP token (no space, ';', quote, path). */
    private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?$");

    /** Strict default: hosted forms may only post to the IdP itself. */
    static String csp() {
        return csp("");
    }

    /**
     * CSP for a hosted page whose form completes an authorization: the POST answers with a
     * 302 to the PAR's (client-authenticated) redirect_uri, and browsers enforce
     * {@code form-action} on every hop of a form submission's redirect chain. With only
     * {@code 'self'} the hop back to the client is blocked silently — the PAR was already
     * consumed, so the next click shows request_uri_invalid. Widen form-action by exactly
     * that one origin; anything unparseable keeps the strict policy.
     */
    static String cspAllowingFormRedirectTo(String redirectUri) {
        String origin = originOf(redirectUri);
        return csp(origin == null ? "" : " " + origin);
    }

    static String originOf(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            URI u = new URI(uri);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (u.getHost() == null || u.getRawUserInfo() != null) {
                return null;
            }
            String origin = scheme + "://" + u.getHost().toLowerCase(Locale.ROOT)
                + (u.getPort() == -1 ? "" : ":" + u.getPort());
            return ORIGIN.matcher(origin).matches() ? origin : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String csp(String extraFormActionSources) {
        return "default-src 'self'; style-src 'self' 'unsafe-inline'; form-action 'self'" + extraFormActionSources
            + "; frame-ancestors 'none'; base-uri 'none'";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(CSP_HEADER, csp());
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        response.setHeader("X-Frame-Options", "DENY");
        chain.doFilter(request, response);
    }
}
