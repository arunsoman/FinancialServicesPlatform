package com.fsp.psp;

import com.fsp.psp.api.PspIdentityFilter;
import com.fsp.psp.config.PspProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * psp-service (:8085) — chapter 13 Stage 5. Owns merchant onboarding, pass-through
 * acquiring sagas, settlement sweeps (closing_debit → merchant payable; clearing MUST be
 * zero each cycle) and recon statement ingest. Fully isolated: own DB (`fsp_psp`), own
 * TigerBeetle book, own outbox, own Flyway history.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PspApplication {
    public static void main(String[] args) {
        SpringApplication.run(PspApplication.class, args);
    }

    /** Zero-trust edge: /v1/** only via gateway (identity headers + shared internal hop credential). */
    @Bean
    FilterRegistrationBean<PspIdentityFilter> pspIdentityFilter(PspProperties props) {
        var reg = new FilterRegistrationBean<>(new PspIdentityFilter(props.getInternalCredential()));
        reg.addUrlPatterns("/v1/*", "/internal/*");
        reg.setOrder(1);
        return reg;
    }
}
