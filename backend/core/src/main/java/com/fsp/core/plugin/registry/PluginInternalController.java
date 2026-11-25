package com.fsp.core.plugin.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.plugin.installation.PluginInstallationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The gateway's view of plugins (internal hop — /internal/** is internal-credential gated by
 * ActorContextFilter; never routed by the gateway).
 *
 *  GET /internal/ops/plugins → { code: { baseUrl, operations: [{id, method, path, permission}] } }
 *      for REGISTERED types (+ SUSPENDED ones, flagged: the gateway answers them 503
 *      PLUGIN_SUSPENDED instead of 404) — the plugin routes the gateway may forward, each with the
 *      permission core will decide on. Taken from the four-eyes-approved manifest, never
 *      from the plugin at runtime.
 *  GET /internal/plugins/{code}/effective?tenantId= → readiness (applies) for the gateway's
 *      503 PLUGIN_NOT_READY check; independent of the caller's permissions.
 */
@Slf4j
@RestController
public class PluginInternalController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PluginTypeRepository types;
    private final PluginInstallationService installations;

    public PluginInternalController(PluginTypeRepository types, PluginInstallationService installations) {
        this.types = types;
        this.installations = installations;
    }

    @GetMapping("/internal/ops/plugins")
    public Map<String, Object> plugins() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PluginType t : types.findAll()) {
            boolean suspended = "SUSPENDED".equals(t.status);
            if (!"REGISTERED".equals(t.status) && !suspended) continue;
            // a REGISTERED version always wins over a suspended one of the same code
            if (suspended && (out.containsKey(t.code) || types.findByCodeAndStatus(t.code, "REGISTERED").size() > 0)) continue;
            List<Map<String, String>> ops = new ArrayList<>();
            try {
                JsonNode manifest = MAPPER.readTree(t.manifest);
                for (JsonNode op : manifest.path("operations")) {
                    Map<String, String> o = new LinkedHashMap<>();
                    o.put("id", op.path("id").asText());
                    o.put("method", op.path("method").asText());
                    o.put("path", op.path("path").asText());
                    o.put("permission", op.path("permission").asText());
                    ops.add(o);
                }
            } catch (Exception e) {
                log.warn("plugin_manifest_unreadable code={} version={}", t.code, t.version);
            }
            out.put(t.code, Map.of("baseUrl", t.baseUrl, "version", t.version, "operations", ops,
                "suspended", suspended));
        }
        return out;
    }

    @GetMapping("/internal/plugins/{code}/effective")
    public Map<String, Object> effective(@PathVariable("code") String code, @RequestParam("tenantId") UUID tenantId) {
        var e = installations.effective(code, tenantId);
        return Map.of("applies", e.applies(), "origin", e.origin() == null ? "" : e.origin());
    }
}
