package com.fsp.recon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * recon-worker (no port) — chapter 13 §8, Stage 6 LANDED. Read-only invariant worker:
 * probes the EMI/PSP internal recon faces over the internal-credential hop, evaluates the
 * cross-book invariants (clearing-zero per cycle, PSD-1 pay-over, EMI float custody) and
 * pages via core's machine-plane op (V29/V31 channel dispatch). No own database, no book
 * mutation, no writes except the page op — by construction.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ReconApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconApplication.class, args);
    }
}
