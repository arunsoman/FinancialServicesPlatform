package com.fsp.plugin.inventory.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Activation readiness (vision §6.6): after an installation is approved (four-eyes) it is
 * PROVISIONING and its routes answer 503 PLUGIN_NOT_READY until the plugin reports every gate.
 * The plugin asks core (as its machine client) which installations wait for it and reports:
 *  - CLIENT_ACTIVE: proven by the DPoP-bound token that made this very call;
 *  - GL_ACCOUNTS_ENSURED: the plugin's CoA rows ensured in the installation's ACTIVE book
 *     (plan decision D5, additive-by-code; the idempotency key is deterministic per tenant+book);
 *     no book there (the platform root) → deferred to each organization's first use;
 *  - PERMISSIONS_SEEDED / SECRETS_PROVISIONED / DEPENDENCIES_READY: this plugin contributes no
 *    per-installation secrets and depends on no other plugin, so these are satisfied by
 *    construction (a plugin that has them must check before reporting).
 */
@Slf4j
@Component
public class ReadinessReporter {

    private static final List<String> GATES = List.of(
        "CLIENT_ACTIVE", "GL_ACCOUNTS_ENSURED", "PERMISSIONS_SEEDED", "SECRETS_PROVISIONED", "DEPENDENCIES_READY");

    private final CoreClient core;
    private final GlBookResolver glResolver;
    private final String code;

    public ReadinessReporter(CoreClient core, GlBookResolver glResolver,
                             @Value("${fsp.plugin.code}") String code) {
        this.core = core;
        this.glResolver = glResolver;
        this.code = code;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 30_000)
    public void report() {
        List<Map<String, Object>> pending;
        try {
            pending = core.provisioning(code);
        } catch (Exception e) {
            log.debug("plugin_readiness_poll_failed detail={}", e.getMessage());
            return;
        }
        for (Map<String, Object> inst : pending) {
            UUID id = UUID.fromString(String.valueOf(inst.get("installationId")));
            UUID tenantId = UUID.fromString(String.valueOf(inst.get("tenantId")));
            try {
                ensureGl(tenantId, id); // first gate: money wiring must exist or every saga would fail
                for (String gate : GATES) core.reportReady(id, gate);
                log.info("plugin_installation_ready_reported installationId={} tenant={}", id, tenantId);
            } catch (Exception e) {
                log.warn("plugin_readiness_report_failed installationId={} detail={}", id, e.getMessage());
            }
        }
    }

    /** Gate GL_ACCOUNTS_ENSURED — the plugin's money wiring is in place for THIS installation.
     *  Installed on an organization with a book → its CoA rows are ensured now. Installed on one
     *  without a book (the platform root, under easy install) → nothing to wire there: every
     *  organization below gets its rows in its own book on first use (GlBookResolver). */
    private void ensureGl(UUID tenantId, UUID installationId) {
        var ledger = glResolver.findRefs(tenantId);
        if (ledger.isEmpty()) {
            log.info("inventory_gl_deferred installationId={} tenantId={} reason=no_book_here "
                + "(each organization's GL accounts are created in its own book on first use)", installationId, tenantId);
            return;
        }
        glResolver.ensureCoa(tenantId, ledger.get().bookId(), true);
    }
}
