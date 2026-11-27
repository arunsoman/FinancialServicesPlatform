package com.fsp.emi;

import com.fsp.emi.api.EmiIdentityFilter;
import com.fsp.emi.config.EmiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * emi-service (:8086) — chapter 13 Stage 0 skeleton. Owns customer wallets (PSD-3 regulated,
 * NAD-only), bill-payment sagas, and closed-loop digital-asset ledgers. Fully isolated:
 * own DB (`fsp_emi`), own TigerBeetle book, own outbox, own Flyway history.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class EmiApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmiApplication.class, args);
    }

    /** Zero-trust edge: /v1/** only via gateway (identity headers + shared internal hop credential). */
    @Bean
    FilterRegistrationBean<EmiIdentityFilter> emiIdentityFilter(EmiProperties props) {
        var reg = new FilterRegistrationBean<>(new EmiIdentityFilter(props.getInternalCredential()));
        reg.addUrlPatterns("/v1/*", "/internal/*");
        reg.setOrder(1);
        return reg;
    }
}
