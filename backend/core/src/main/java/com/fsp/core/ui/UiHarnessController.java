package com.fsp.core.ui;

import com.fsp.core.common.Actor;
import com.fsp.core.common.FspOperation;
import com.fsp.core.rbac.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Widget harness operations (plans/widget-harness-prd.md §4). Mutating endpoints carry
 * Idempotency-Key via IdempotencyFilter; four-eyes publish goes through /v1/approvals
 * (OpType UI_LAYOUT_PUBLISH), never a bespoke endpoint.
 */
@RestController
@RequestMapping("/v1/ui")
public class UiHarnessController {

    private final WidgetRegistryService registry;
    private final PortalLayoutService layouts;
    private final PortalMenuService menus;
    private final ApiDiscoveryService discovery;

    public UiHarnessController(WidgetRegistryService registry, PortalLayoutService layouts,
                               PortalMenuService menus, ApiDiscoveryService discovery) {
        this.registry = registry;
        this.layouts = layouts;
        this.menus = menus;
        this.discovery = discovery;
    }

    @FspOperation(id = "ui.widget.register", component = "ui", risk = FspOperation.OperationRisk.WRITE,
            permission = "ui.widget.register", resource = "TENANT", title = "Register or update a UI widget")
    @PutMapping("/widgets")
    public UiDtos.WidgetView register(HttpServletRequest request, @RequestBody UiDtos.WidgetRegistration body) {
        Actor actor = actor(request);
        return toView(registry.register(body, actor));
    }

    @FspOperation(id = "ui.widget.list", component = "ui", risk = FspOperation.OperationRisk.READ,
            permission = "ui.widget.register", resource = "TENANT", title = "List registered UI widgets")
    @GetMapping("/widgets")
    public List<UiDtos.WidgetView> list(HttpServletRequest request) {
        Actor actor = actor(request);
        return registry.list(actor).stream().map(UiHarnessController::toView).toList();
    }

    /** Live backend API surface (method + path + signature + @FspOperation metadata) for
     *  the widget workbench LLM prompt — every @RestController handler the system exposes right now. */
    @FspOperation(id = "ui.backend.apis.read", component = "ui", risk = FspOperation.OperationRisk.READ,
            permission = "", resource = "TENANT", title = "Discover the live backend API surface (paths + signatures)")
    @GetMapping("/backend-apis")
    public List<UiDtos.BackendApiDto> backendApis(HttpServletRequest request) {
        actor(request);
        return discovery.discover(request);
    }

    @FspOperation(id = "ui.layout.draft.save", component = "ui", risk = FspOperation.OperationRisk.FOUR_EYES,
            permission = "ui.layout.manage", resource = "TENANT", title = "Save a portal layout draft")
    @PutMapping("/layouts/{portal}/draft")
    public UiDtos.LayoutView saveDraft(@PathVariable String portal,
                                       @RequestBody UiDtos.LayoutSaveRequest body, HttpServletRequest request) {
        return toView(layouts.saveDraft(portal, body, actor(request)));
    }

    @FspOperation(id = "ui.layout.draft.read", component = "ui", risk = FspOperation.OperationRisk.READ,
            permission = "ui.layout.manage", resource = "TENANT", title = "Read the draft layout")
    @GetMapping("/layouts/{portal}/draft")
    public UiDtos.LayoutView draft(@PathVariable String portal, HttpServletRequest request) {
        return toView(layouts.draft(portal, actor(request)));
    }

    @FspOperation(id = "ui.layout.publish.submit", component = "ui", risk = FspOperation.OperationRisk.FOUR_EYES,
            permission = "ui.layout.manage", resource = "TENANT", title = "Submit a draft layout for publishing")
    @PostMapping("/layouts/{portal}/submit")
    public UiDtos.LayoutView submit(@PathVariable String portal, HttpServletRequest request) {
        return toView(layouts.submit(portal, actor(request)));
    }

    @FspOperation(id = "ui.layout.published.read", component = "ui", risk = FspOperation.OperationRisk.READ,
            permission = "", resource = "TENANT", title = "Read the published portal layout")
    @GetMapping("/layouts/{portal}/published")
    public UiDtos.LayoutView published(@PathVariable String portal, HttpServletRequest request) {
        return layouts.published(portal, actor(request));
    }

    // -------- portal menus --------

    /** A portal's published menus + the widgets that work in it (any signed-in person). */
    @FspOperation(id = "ui.menu.read", component = "ui", risk = FspOperation.OperationRisk.READ,
            permission = "", resource = "TENANT", title = "Read a portal's published menus")
    @GetMapping("/menus/{portal}")
    public java.util.Map<String, Object> menu(@PathVariable String portal, HttpServletRequest request) {
        actor(request); // authenticated-only
        return menus.menu(portal);
    }

    private Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) {
            throw com.fsp.core.common.ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        }
        return actor;
    }

    private static UiDtos.WidgetView toView(UiWidget w) {
        return new UiDtos.WidgetView(w.id, w.version, w.label, w.category, w.bundle,
            parse(w.portals), parse(w.requires), w.bundleSha256, w.status, w.registeredBy,
            w.updatedAt.toString(), w.createdAt == null ? null : w.createdAt.toString());
    }

    private static UiDtos.LayoutView toView(UiLayout l) {
        return new UiDtos.LayoutView(l.id, l.portal, l.status, l.layoutJson, l.schemaVersion,
            l.makerId, l.updatedAt.toString(), l.versionNum);
    }

    private static List<String> parse(String json) {
        String body = json.trim();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        return body.isBlank() ? List.of()
            : java.util.Arrays.stream(body.split(","))
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .toList();
    }
}
