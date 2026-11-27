package com.fsp.idp;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.Filter;

/** Registers the IdP response hardening filter (red-team round 2 hardening slice). */
@Configuration
public class IdpHardeningConfig {

    @Bean
    public FilterRegistrationBean<Filter> idpHardeningFilter() {
        var reg = new FilterRegistrationBean<Filter>(new IdpHardeningFilter());
        reg.addUrlPatterns("/idp/*");
        reg.setOrder(10);
        return reg;
    }
}
