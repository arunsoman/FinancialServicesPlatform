package com.fsp.bff;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Session infrastructure (FAPI2 guide §1): HttpOnly SameSite cookie + CSRF double-submit on
 * session-authenticated writes. Sender-constrained tokens never enter the browser — the SPA
 * only ever holds this session cookie. Core's internal credential lives only in BFF config.
 */
@Configuration
@EnableWebSecurity
public class BffSecurityConfig {

    @Bean
    public SecurityFilterChain bffSecurityFilterChain(HttpSecurity http, BffProperties props) throws Exception {
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()) // session model enforced per-route in controllers
            .httpBasic(h -> h.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable());
        return http.build();
    }

    /** Red-team (rounds 2–3) hardening: origin enforcement + baseline security headers. */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> bffHardeningFilter(BffProperties props) {
        var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>(
            new OriginEnforcementFilter(props.getAllowedOrigins()));
        reg.addUrlPatterns("/*");
        reg.setOrder(10);
        return reg;
    }

    /** Referrer-Policy + Permissions-Policy on every BFF response (round 2 wave 10a gap). */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> bffHeadersFilter() {
        var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>(
            new jakarta.servlet.Filter() {
                @Override public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res,
                                               jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                    var r = (jakarta.servlet.http.HttpServletResponse) res;
                    r.setHeader("Referrer-Policy", "no-referrer");
                    r.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
                    chain.doFilter(req, res);
                }
            });
        reg.addUrlPatterns("/*");
        reg.setOrder(20);
        return reg;
    }
}
