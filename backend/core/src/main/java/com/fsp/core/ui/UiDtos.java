package com.fsp.core.ui;

import java.util.List;
import java.util.UUID;

/** Wire DTOs for the widget harness (§2b: records in a final container, private ctor). */
public final class UiDtos {

    private UiDtos() {
    }

    // ---------- widget registration ----------

    /** PUT /v1/ui/widgets — idempotent on the widget id (re-register = version bump).
     *  bundleSha256: optional "sha256:<64 hex>" of the BUILT asset chunk; embeds into the
     *  registry (recorded with the widget; not checked by the portal). */
    public record WidgetRegistration(
            String id, String version, String label, String category, String bundle,
            List<String> portals, List<String> requires, String bundleSha256) {
    }

    public record WidgetView(
            String id, String version, String label, String category, String bundle,
            List<String> portals, List<String> requires, String bundleSha256, String status,
            UUID registeredBy, String updatedAt, String createdAt) {
    }

    // ---------- portal layouts ----------

    /** PUT /v1/ui/layouts/{portal}/draft — the builder's whole layout in one save. */
    public record LayoutSaveRequest(String layoutJson, int schemaVersion) {
    }

    public record LayoutView(
            UUID id, String portal, String status, String layoutJson, int schemaVersion,
            UUID makerId, String updatedAt, Long versionNum) {
    }

    public record LayoutSummary(
            String portal, String status, UUID makerId, String updatedAt, UUID approvalId) {
    }

    // ---------- backend API discovery (widget workbench LLM prompt) ----------

    /** One discovered handler: HTTP method + path + signature + its @FspOperation metadata. */
    public record BackendApiDto(
            String controller, String method, String httpMethod, String path,
            String operationId, String permission, String component, String risk, String title,
            List<BackendParamDto> params) {
    }

    /** One handler parameter: name, Java simple type, where it binds from, required flag. */
    public record BackendParamDto(String name, String type, String location, boolean required) {
    }
}
