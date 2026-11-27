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

    /** Gate GL_ACCOUNTS_ENSURED — additive-by-code CoA ensure (decision D5); the key is
     *  deterministic per tenant + book inside CoreClient. */
    private void ensureGl(UUID tenantId, UUID installationId) {
        var ledger = glResolver.refs(tenantId);
        List<Map<String, Object>> rows = CoaRows.rows(tenantId).stream()
            .<Map<String, Object>>map(r -> Map.of(
                "code", r.code(), "name", r.name(), "accountType", r.accountType(),
                "normalBalance", r.normalBalance(), "balanceBound", r.balanceBound()))
            .toList();
        core.ensureGlAccounts(tenantId, ledger.bookId(), rows);
        log.info("inventory_gl_accounts_ensured tenantId={} book={}", tenantId, ledger.bookId());
    }
}
