package com.fsp.core.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds and signs portal delivery bundles (plans/signed-portal-delivery-plan.md S1).
 * One bundle per portal — PUBLISHED layout + widget catalog (each widget carrying the
 * sha256 of its BUILT asset chunk) — canonicalized, digested, ES256-signed and persisted
 * (apend-only, one current row per portal). Regenerated on every publish and on widget
 * registration changes that affect that portal's catalog. The bundle is what every portal
 * verifies verify-then-render in slice S2.
 */
@Slf4j
@Service
public class UiBundleService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UiLayoutRepository layouts;
    private final UiWidgetRepository widgets;
    private final UiBundleRepository bundles;
    private final UiSigner signer;

    public UiBundleService(UiLayoutRepository layouts, UiWidgetRepository widgets,
                           UiBundleRepository bundles, UiSigner signer) {
        this.layouts = layouts;
        this.widgets = widgets;
        this.bundles = bundles;
        this.signer = signer;
    }

    /** (Re)generate the signed bundle for a portal with a PUBLISHED layout. Skips silently
     *  (DEBUG) when nothing is published — registries and drafts never mint bundles. */
    @Transactional
    public UiBundle generate(String portal) {
        UiLayout published = layouts.findFirstByPortalAndStatus(portal, UiLayout.Status.PUBLISHED)
            .orElse(null);
        if (published == null) {
            log.debug("ui_bundle_skipped portal={} reason=no_published_layout", portal);
            return null;
        }
        try {
            String payloadCanonical = canonicalPayload(portal, published);
            String digest = signer.digest(payloadCanonical);
            String jws = signer.sign(payloadCanonical);

            bundles.markHistorical(portal);
            UiBundle row = new UiBundle();
            row.id = UUID.randomUUID();
            row.portal = portal;
            row.current = true;
            row.kid = signer.kid();
            row.alg = signer.alg();
            row.digest = digest;
            row.payload = payloadCanonical;
            row.jws = jws;
            row.layoutId = published.id;
            row.createdAt = Instant.now();
            bundles.save(row);
            log.info("ui_bundle_signed portal={} bundleId={} kid={} digest={} layoutId={}",
                portal, row.id, row.kid, digest, published.id);
            return row;
        } catch (UncheckedIOException e) {
            // canonicalization failure = corrupt persisted layout; this is a publish-time P1
            log.error("ui_bundle_canonical_failed portal={} layoutId={} ex={}", portal,
                published.id, e.getMessage());
            throw ApiException.badRequest("UI_BUNDLE_INVALID", "stored layout is not valid JSON");
        }
    }

    @Transactional(readOnly = true)
    public UiDtos.BundleView current(String portal) {
        UiBundle row = bundles.findByPortalAndCurrentTrue(portal)
            .orElseThrow(() -> ApiException.notFound("UI_BUNDLE_NOT_FOUND",
                "No signed bundle for portal " + portal));
        return new UiDtos.BundleView(row.id, row.portal, row.kid, row.alg,
            row.digest, row.payload, row.jws);
    }

    @Transactional
    public void regenerateForWidgets(List<String> portals) {
        for (String portal : portals) {
            generate(portal);
        }
    }

    /** Re-assembly of the canonical payload from the CURRENT persisted world (layout +
     *  widget registry) — the checkpoint job's cross-verification input. Null when the
     *  portal has no PUBLISHED layout (nothing should be signed for it). */
    @Transactional(readOnly = true)
    public String canonicalPayloadFor(String portal) {
        UiLayout published = layouts.findFirstByPortalAndStatus(portal, UiLayout.Status.PUBLISHED)
            .orElse(null);
        return published == null ? null : canonicalPayload(portal, published);
    }

    // ------------------------------------------------------------ payload assembly

    private String canonicalPayload(String portal, UiLayout published) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            JsonNode layoutTree = MAPPER.readTree(published.layoutJson);
            JsonNode menus = layoutTree.has("menus") ? layoutTree.get("menus") : MAPPER.nullNode();
            payload.put("portal", portal);
            payload.put("schemaVersion", 1);
            payload.put("layoutVersion", published.id.toString());
            // microseconds: what Postgres keeps. The publisher signs its in-memory row (nanoseconds);
            // the checkpoint and boot sync re-assemble from the stored row — both must agree.
            payload.put("publishedAt", published.updatedAt.truncatedTo(ChronoUnit.MICROS).toString());
            payload.put("menus", menus);
            List<Map<String, Object>> widgetList = new ArrayList<>();
            for (UiWidget w : widgets.findAllByOrderByIdAsc()) {
                List<String> widgetPortals = readStringArray(w.portals);
                if (!widgetPortals.contains(portal) || !"ACTIVE".equals(w.status)) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", w.id);
                entry.put("version", w.version);
                entry.put("bundle", w.bundle);
                entry.put("bundleHash", w.bundleSha256 == null ? "sha256:unregistered" : w.bundleSha256);
                entry.put("requires", readStringArray(w.requires));
                widgetList.add(entry);
            }
            payload.put("widgets", widgetList);
            return signer.canonicalize(MAPPER.writeValueAsString(payload));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readStringArray(String json) {
        try {
            List<String> out = new ArrayList<>();
            for (JsonNode n : MAPPER.readTree(json)) {
                out.add(n.asText());
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }
}
