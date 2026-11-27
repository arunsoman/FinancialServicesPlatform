package com.fsp.emi.recon;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custody snapshot face on the INTERNAL plane ({@code /internal/recon/snapshot}): guarded
 * by EmiIdentityFilter's credential-only rule for /internal/** (same trust model as the
 * gateway's catalog fetch); never routed by the gateway, never in the operation catalog.
 */
@RestController
public class EmiReconController {

    private final EmiReconService service;

    public EmiReconController(EmiReconService service) {
        this.service = service;
    }

    @GetMapping("/internal/recon/snapshot")
    public EmiReconSnapshot.Snapshot snapshot() {
        return service.snapshot();
    }
}
