package com.fsp.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Route policy derived from the operation catalog (guide §3/§6): the gateway forwards only
 * catalog-classified operations and rejects unknown routes BEFORE they can reach the core.
 * Boot fails when the catalog cannot be fetched — an un-verified edge is worse than a dark edge.
 *
 * Plugin upstream routing (plugin-platform-plan P2): routes under /v1/plugins/<code>/ are
 * forwarded to the plugin's baseUrl (from core's /internal/ops/plugins). A readiness check
 * against core's effective-installation endpoint gates forwarding — 503 PLUGIN_NOT_READY
 * when the installation is not ACTIVE.
 */
@Component
public class CatalogRouteTable {

    private static final Logger log = LoggerFactory.getLogger(CatalogRouteTable.class);

    private static final Pattern PLUGIN_PATH = Pattern.compile("^/v1/plugins/([^/]+)/.*$");

    public record Route(String method, String pathTemplate, Pattern matcher, String operationId) {}

    /** A plugin-contributed operation from the REGISTERED manifest (core /internal/ops/plugins). */
    public record PluginOp(String code, String baseUrl, String method, String pathTemplate, Pattern matcher,
                           String operationId, String permission) {}

    /** A PLATFORM-SERVICE operation (emi/psp) from the service's own descriptor at its /internal/ops/catalog. */
    public record ServiceOp(String component, String baseUrl, String method, String pathTemplate,
                            Pattern matcher, String operationId, String permission) {}

    /** plugin operations, rebuilt on every refresh — only these plugin routes are forwarded */
    private final CopyOnWriteArrayList<PluginOp> pluginOps = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<ServiceOp> serviceOps = new CopyOnWriteArrayList<>();

    private final GatewayProperties props;
    private final RestClient rest;
    private volatile String digest = "unpublished";
    private final CopyOnWriteArrayList<Route> routes = new CopyOnWriteArrayList<>();
    // plugin code → baseUrl (from core's /internal/ops/plugins)
    private final CopyOnWriteArrayList<Map.Entry<String, String>> pluginUrls =
        new CopyOnWriteArrayList<>();

    public CatalogRouteTable(GatewayProperties props) {
        this.props = props;
        this.rest = RestClient.create();
        retryUntilCatalog(true);
    }

    /** Boot mandate: a cold gateway retries until core's catalog is reachable, then fails loudly. */
    private void retryUntilCatalog(boolean boot) {
        int attempts = boot ? 30 : 1;
        for (int i = 0; i < attempts; i++) {
            refresh();
            if (!routes.isEmpty()) {
                return;
            }
            if (log.isWarnEnabled()) {
                log.warn("gateway_catalog_retry attempt={} of {} core={}", i + 1, attempts,
                    props.getCoreBaseUrl());
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (routes.isEmpty()) {
            throw new IllegalStateException("gateway_boot_failed_empty_catalog "
                + props.getCoreBaseUrl() + "/internal/ops/catalog");
        }
    }

    /** Fetch the descriptor from core and rebuild the matcher set. Also loads plugin baseUrls. */
    public void refresh() {
        boolean refreshed = false;
        try {
            ResponseEntity<String> res = rest.method(HttpMethod.GET)
                .uri(props.getCoreBaseUrl() + "/internal/ops/catalog")
                .header("X-FSP-Internal-Credential", props.getInternalCredential())
                .retrieve().toEntity(String.class);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getBody());
                String newDigest = root.path("digest").asText("unknown");
                CopyOnWriteArrayList<Route> rebuilt = new CopyOnWriteArrayList<>();
                root.path("operations").forEach(op -> {
                    String method = op.path("method").asText();
                    String path = op.path("pathPattern").asText();
                    String id = op.path("operationId").asText();
                    if (method != null && !method.isBlank() && path.startsWith("/v1/")) {
                        rebuilt.add(new Route(method, path, compile(path), id));
                    }
                });
                if (!rebuilt.isEmpty()) {
                    routes.retainAll(rebuilt);
                    for (Route r : rebuilt) {
                        if (!routes.contains(r)) {
                            routes.addIfAbsent(r);
                        }
                    }
                    digest = newDigest;
                }
                log.info("gateway_catalog_updated digest={} routes={}", digest, routes.size());
                refreshed = true;
            } else {
                log.warn("gateway_catalog_fetch_failed status={}", res.getStatusCode().value());
            }
        } catch (Exception e) {
            log.warn("gateway_catalog_refresh_failed detail={}", e.getMessage());
        }
        refreshPluginUrls();
        refreshServiceOps();
        if (!refreshed && routes.isEmpty()) {
            log.warn("gateway_refresh_no_routes core={}", props.getCoreBaseUrl());
        }
    }

    /** Load plugin code → baseUrl from core's /internal/ops/plugins (internal credential). */
    private void refreshPluginUrls() {
        try {
            ResponseEntity<String> res = rest.method(HttpMethod.GET)
                .uri(props.getCoreBaseUrl() + "/internal/ops/plugins")
                .header("X-FSP-Internal-Credential", props.getInternalCredential())
                .retrieve().toEntity(String.class);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getBody());
                CopyOnWriteArrayList<Map.Entry<String, String>> rebuilt =
                    new CopyOnWriteArrayList<>();
                List<PluginOp> ops = new java.util.ArrayList<>();
                root.fields().forEachRemaining(entry -> {
                    String code = entry.getKey();
                    var v = entry.getValue();
                    String baseUrl = v.isTextual() ? v.asText() : v.path("baseUrl").asText();
                    rebuilt.add(new java.util.AbstractMap.SimpleEntry<>(code, baseUrl));
                    String prefix = "/v1/plugins/" + code + "/";
                    v.path("operations").forEach(op -> {
                        String path = op.path("path").asText();
                        String method = op.path("method").asText().toUpperCase();
                        String perm = op.path("permission").asText();
                        // a manifest may only contribute routes under its own prefix, each with a permission
                        if (path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() - 1))) {
                            if (!perm.isBlank() && !method.isBlank()) {
                                ops.add(new PluginOp(code, baseUrl, method, path, compile(path),
                                    op.path("id").asText(), perm));
                            }
                        } else {
                            log.warn("gateway_plugin_op_rejected plugin={} path={} reason=outside_prefix", code, path);
                        }
                    });
                });
                pluginOps.clear();
                pluginOps.addAll(ops);
                pluginUrls.retainAll(rebuilt);
                for (Map.Entry<String, String> e : rebuilt) {
                    boolean found = false;
                    for (Map.Entry<String, String> existing : pluginUrls) {
                        if (existing.getKey().equals(e.getKey())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        pluginUrls.addIfAbsent(e);
                    }
                }
                log.info("gateway_plugin_urls_loaded plugins={} operations={}", pluginUrls.size(), pluginOps.size());
            }
        } catch (Exception e) {
            log.warn("gateway_plugin_urls_refresh_failed detail={}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 60000)
    public void scheduledRefresh() {
        refresh();
    }

    public boolean matches(String method, String path) {
        return routes.stream().anyMatch(r ->
            r.matcher().matcher(path).matches() && r.method().equalsIgnoreCase(method));
    }

    /** The plugin operation a request maps to — never a core catalog route (core wins). */
    public java.util.Optional<PluginOp> matchPlugin(String method, String path) {
        if (matches(method, path)) return java.util.Optional.empty();
        return pluginOps.stream()
            .filter(o -> o.method().equalsIgnoreCase(method) && o.matcher().matcher(path).matches())
            .findFirst();
    }

    /** The platform-service operation a request maps to — never core/plugin routes (they win). */
    public java.util.Optional<ServiceOp> matchService(String method, String path) {
        if (matches(method, path)) return java.util.Optional.empty();
        return serviceOps.stream()
            .filter(o -> o.method().equalsIgnoreCase(method) && o.matcher().matcher(path).matches())
            .findFirst();
    }

    public int serviceOpsCount() { return serviceOps.size(); }

    /**
     * Fetch platform-service operation descriptors (EMI, PSP — credentials-gated) and build
     * their routes. Same trust model as core's signed catalog: only /v1-prefixed,
     * explicitly-method'd operations are routable, each under the service's EXCLUSIVE
     * /v1/&lt;component&gt;/** namespace.
     */
    private void refreshServiceOps() {
        List<ServiceOp> merged = new java.util.ArrayList<>();
        merged.addAll(fetchServiceOps("emi", props.getEmiBaseUrl(), "/v1/emi/", "gateway_emi",
            props.getEmiInternalCredential()));
        merged.addAll(fetchServiceOps("psp", props.getPspBaseUrl(), "/v1/psp/", "gateway_psp",
            props.getPspInternalCredential()));
        serviceOps.clear();
        serviceOps.addAll(merged);
    }

    private List<ServiceOp> fetchServiceOps(String component, String baseUrl,
                                            String namespace, String logPrefix, String credential) {
        try {
            ResponseEntity<String> res = rest.method(HttpMethod.GET)
                .uri(baseUrl + "/internal/ops/catalog")
                .header("X-FSP-Internal-Credential", credential)
                .retrieve().toEntity(String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                log.warn(logPrefix + "_catalog_fetch_failed status={}", res.getStatusCode().value());
                return List.of();
            }
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getBody());
            List<ServiceOp> rebuilt = new java.util.ArrayList<>();
            root.path("operations").forEach(op -> {
                String method = op.path("method").asText().toUpperCase();
                String path = op.path("pathPattern").asText();
                String id = op.path("operationId").asText();
                String perm = op.path("permission").asText();
                if (path.startsWith(namespace) && !method.isBlank() && !id.isBlank()) {
                    rebuilt.add(new ServiceOp(component, baseUrl, method, path,
                        compile(path), id, perm));
                } else {
                    log.warn(logPrefix + "_op_rejected op={} path={} reason=outside_namespace", id, path);
                }
            });
            log.info(logPrefix + "_catalog_updated operations={}", rebuilt.size());
            return rebuilt;
        } catch (Exception e) {
            log.warn(logPrefix + "_catalog_refresh_failed detail={}", e.getMessage());
            return List.of();
        }
    }

    public String digest() {
        return digest;
    }

    public int size() {
        return routes.size();
    }

    private static Pattern compile(String template) {
        String regex = template
            .replaceAll("\\{[^/]+}", "[^/]+")
            .replace(".", "\\.");
        return Pattern.compile("^" + regex + "$");
    }
}
