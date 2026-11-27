package com.fsp.core.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A portal's menus as the portal renders them: the PUBLISHED layout (from the repo seed or the
 * builder) plus the active widgets that work in that portal. Read straight from the database on
 * every request — nothing is signed, stored separately or re-generated.
 *
 * Shape: { portal, schemaVersion, layoutVersion, publishedAt,
 *          menus:[{ id, label, icon?, screens:[{ id, label, placedWidgets:[…] }] }],
 *          widgets:[{ id, version, bundle, bundleHash, requires[] }] }
 *
 * The menus only steer navigation: core still checks every API call a widget makes.
 */
@Slf4j
@Service
public class PortalMenuService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UiLayoutRepository layouts;
    private final UiWidgetRepository widgets;

    public PortalMenuService(UiLayoutRepository layouts, UiWidgetRepository widgets) {
        this.layouts = layouts;
        this.widgets = widgets;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> menu(String portal) {
        UiLayout published = layouts.findFirstByPortalAndStatus(portal, UiLayout.Status.PUBLISHED)
            .orElseThrow(() -> ApiException.notFound("UI_MENU_NOT_FOUND", "No published menus for portal " + portal));
        JsonNode tree;
        try {
            tree = MAPPER.readTree(published.layoutJson);
        } catch (IOException e) {
            log.error("ui_menu_layout_unreadable portal={} layoutId={}", portal, published.id);
            throw ApiException.badRequest("UI_MENU_INVALID", "The published layout is not valid JSON");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("portal", portal);
        out.put("schemaVersion", 1);
        out.put("layoutVersion", published.id.toString());
        out.put("publishedAt", published.updatedAt.truncatedTo(ChronoUnit.MICROS).toString());
        // plain lists/maps, never a JsonNode: the web layer serializes with Jackson 3, which would
        // write a Jackson 2 tree as a bean ({"array":true,…}) instead of as JSON
        out.put("menus", tree.has("menus") ? MAPPER.convertValue(tree.get("menus"), List.class) : List.of());
        List<Map<String, Object>> list = new ArrayList<>();
        for (UiWidget w : widgets.findAllByOrderByIdAsc()) {
            if (!"ACTIVE".equals(w.status) || !strings(w.portals).contains(portal)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", w.id);
            entry.put("version", w.version);
            entry.put("bundle", w.bundle);
            entry.put("bundleHash", w.bundleSha256 == null ? "sha256:unregistered" : w.bundleSha256);
            entry.put("requires", strings(w.requires));
            list.add(entry);
        }
        out.put("widgets", list);
        return out;
    }

    private static List<String> strings(String json) {
        try {
            List<String> out = new ArrayList<>();
            for (JsonNode n : MAPPER.readTree(json)) out.add(n.asText());
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }
}
