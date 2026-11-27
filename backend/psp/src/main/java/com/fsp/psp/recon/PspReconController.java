package com.fsp.psp.recon;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recon snapshot face on the INTERNAL plane ({@code /internal/recon/snapshot}): guarded by
 * the zero-trust edge's credential check only (PspIdentityFilter covers every request;
 * /internal/** is credential-authenticated like the gateway's catalog fetch and is never
 * routed by the gateway nor present in the operation catalog). Read-only, money as string.
 */
@RestController
public class PspReconController {

    private final PspReconService service;

    public PspReconController(PspReconService service) {
        this.service = service;
    }

    @GetMapping("/internal/recon/snapshot")
    public PspReconSnapshot.Snapshot snapshot() {
        return service.snapshot();
    }
}
