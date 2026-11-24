package com.fsp.core.ui;

import com.fsp.core.audit.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Menus ship with the code (docs/portal-menus.md): the repo's {@code R__seed_portal_menus.sql}
 * writes each portal's PUBLISHED layout on deploy, and THIS step signs it — so a deploy needs
 * no manual publish, in any environment, with each environment's own signing key.
 *
 * <p>At boot (after Flyway, before the web server takes traffic) every portal with a PUBLISHED
 * layout gets a fresh signed bundle when it has none, when the current bundle no longer
 * matches the persisted world (the seed changed the layout or the widget registry), or when
 * its signature does not verify under this environment's key (re-keyed). A bundle that still
 * matches is left alone, so a plain restart signs nothing. Each signing is audited.
 * A failure is logged and skipped: that portal fails closed in the browser (no/old bundle),
 * core itself still boots.</p>
 */
@Slf4j
@Component
public class UiBundleBootSync implements SmartInitializingSingleton {

    static final String AUDIT_ACTION = "UI_BUNDLE_SIGNED_AT_BOOT";

    private final UiLayoutRepository layouts;
    private final UiBundleRepository bundles;
    private final UiBundleService assembly;
    private final UiSigner signer;
    private final AuditService audit;

    public UiBundleBootSync(UiLayoutRepository layouts, UiBundleRepository bundles,
                            UiBundleService assembly, UiSigner signer, AuditService audit) {
        this.layouts = layouts;
        this.bundles = bundles;
        this.assembly = assembly;
        this.signer = signer;
        this.audit = audit;
    }

    @Override
    public void afterSingletonsInstantiated() {
        sync();
    }

    /** Signs every portal whose bundle is missing or stale; returns how many were signed. */
    public int sync() {
        int signed = 0;
        int current = 0;
        for (UiLayout published : layouts.findAllByStatus(UiLayout.Status.PUBLISHED)) {
            String portal = published.portal;
            try {
                String want = signer.digest(assembly.canonicalPayloadFor(portal));
                UiBundle bundle = bundles.findByPortalAndCurrentTrue(portal).orElse(null);
                String have = bundle == null ? null : bundle.digest;
                // current = same world AND signed by this environment's key (a re-keyed
                // environment — new pki, restarted core — re-signs here, no manual step)
                if (want.equals(have) && signer.verify(bundle.payload, bundle.jws)) {
                    current++;
                    continue;
                }
                UiBundle fresh = assembly.generate(portal);
                if (fresh == null) continue;
                signed++;
                audit.record(null, null, null, null, AUDIT_ACTION, "UI_BUNDLE", fresh.id.toString(),
                    have, fresh.digest, false);
                log.info("ui_bundle_boot_signed portal={} bundleId={} before={} after={}",
                    portal, fresh.id, have, fresh.digest);
            } catch (RuntimeException e) {
                // another core instance signing the same portal at the same moment lands here too
                // (ux_ui_bundles_current) — its bundle is the same world, so nothing is lost
                log.error("ui_bundle_boot_sign_failed portal={} ex={} msg=\"{}\"",
                    portal, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("ui_bundle_boot_sync signed={} current={}", signed, current);
        return signed;
    }
}
