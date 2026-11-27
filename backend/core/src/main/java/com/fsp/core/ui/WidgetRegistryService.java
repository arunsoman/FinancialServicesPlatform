package com.fsp.core.ui;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.rbac.PermissionRepository;
import com.fsp.core.rbac.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Registration of UI widgets (plans/widget-harness-prd.md §4). Idempotent per widget id;
 * re-registering upserts the definition under optimistic locking. Requires the caller to
 * hold {@code ui.widget.register} and REFUSES unknown permission cells — a widget can
 * never gate an action on a permission the catalogue does not define (guide §3: a new
 * permission starts with zero grants and must be created deliberately).
 */
@Slf4j
@Service
public class WidgetRegistryService {

    private static final Pattern WIDGET_ID = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*){1,3}$");
    private static final Pattern WIDGET_VERSION = Pattern.compile("^[0-9A-Za-z.\\-]{1,32}$");
    private static final Pattern BUNDLE_SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final UiWidgetRepository widgets;
    private final PermissionRepository permissions;
    private final PermissionService permissionService;

    public WidgetRegistryService(UiWidgetRepository widgets, PermissionRepository permissions,
                                 PermissionService permissionService) {
        this.widgets = widgets;
        this.permissions = permissions;
        this.permissionService = permissionService;
    }

    @Transactional
    public UiWidget register(UiDtos.WidgetRegistration dto, Actor actor) {
        permissionService.require(actor, "ui.widget.register", actor.activeTenantId());
        if (dto.id() == null || !WIDGET_ID.matcher(dto.id()).matches()) {
            throw ApiException.badRequest("UI_WIDGET_ID",
                "widget id must be '<area>.<name>' of lower-case dash-separated tokens");
        }
        if (dto.version() == null || !WIDGET_VERSION.matcher(dto.version()).matches()) {
            throw ApiException.badRequest("UI_WIDGET_VERSION", "version required (max 32 chars)");
        }
        if (dto.label() == null || dto.label().isBlank()
            || dto.category() == null || dto.category().isBlank()
            || dto.bundle() == null || dto.bundle().isBlank()) {
            throw ApiException.badRequest("UI_WIDGET_FIELDS", "label, category and bundle are required");
        }
        List<String> portals = dto.portals() == null ? List.of() : dto.portals();
        List<String> requires = dto.requires() == null ? List.of() : dto.requires();
        if (dto.bundleSha256() != null && !BUNDLE_SHA256.matcher(dto.bundleSha256()).matches()) {
            throw ApiException.badRequest("UI_WIDGET_BAD_HASH",
                "bundleSha256 must be 'sha256:' + 64 lowercase hex chars");
        }
        for (String cell : requires) {
            boolean known = permissions.findById(cell).isPresent();
            if (!known) {
                log.warn("ui_widget_unknown_cell widget={} cell={} actor={}", dto.id(), cell, actor.username());
                throw ApiException.badRequest("UI_WIDGET_UNKNOWN_CELL",
                    "permission cell '%s' is not in the permission catalog — create it deliberately first".formatted(cell));
            }
        }

        UiWidget row = widgets.findById(dto.id()).orElseGet(() -> {
            UiWidget fresh = new UiWidget();
            fresh.id = dto.id();
            fresh.createdAt = Instant.now();
            return fresh;
        });
        row.version = dto.version();
        row.label = dto.label();
        row.category = dto.category();
        row.bundle = dto.bundle();
        row.portals = toJson(portals);
        row.requires = toJson(requires);
        row.bundleSha256 = dto.bundleSha256();
        row.status = "ACTIVE";
        row.registeredBy = actor.personId();
        row.updatedAt = Instant.now();
        widgets.save(row);
        log.info("ui_widget_registered widget={} version={} requires={} bundleSha256={} by={}",
            row.id, row.version, requires, row.bundleSha256, actor.username());
        return row;
    }

    /** List requires no specific permission beyond authentication (catalog is not sensitive). */
    @Transactional(readOnly = true)
    public List<UiWidget> list(Actor actor) {
        if (actor == null) {
            throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        }
        return widgets.findAllByOrderByIdAsc();
    }

    private static String toJson(List<String> values) {
        return "[" + values.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]";
    }
}
