package com.fsp.core.plugin.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.common.ApiException;
import com.fsp.core.config.FspProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * POST /internal/release/plugins — the deploy registers the plugins its release ships
 * ("plugins ship as code", AGENTS.md §2.1 exception, docs/plugin-registration.md).
 *
 * Two locks: the internal credential (ActorContextFilter, like every /internal route) AND the
 * release credential, which only the deploy holds — the gateway and BFF never see it, so a caller
 * that can reach core's internal plane still cannot register a plugin. Never routed by the gateway.
 *
 * The body is taken as text and parsed here, after the credential check: core's message converter
 * is Jackson 3 and cannot build the Jackson 2 JsonNode the lifecycle service works with.
 *
 * Body: { release, provenance: {commit, pr, author, reviewers[], deployApprovers[], ...},
 *         suspendMissing, plugins: [{code, version, baseUrl, manifest}] }
 */
@Slf4j
@RestController
public class PluginReleaseController {

    static final String RELEASE_CREDENTIAL = "X-FSP-Release-Credential";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PluginLifecycleService lifecycle;
    private final FspProperties props;

    public PluginReleaseController(PluginLifecycleService lifecycle, FspProperties props) {
        this.lifecycle = lifecycle;
        this.props = props;
    }

    @PostMapping("/internal/release/plugins")
    public Map<String, Object> reconcile(HttpServletRequest request, @RequestBody String rawBody) {
        String expected = props.getSecurity().getReleaseCredential();
        if (expected == null || expected.isBlank()) {
            log.warn("plugin_release_closed reason=credential_not_configured");
            throw ApiException.forbidden("RELEASE_REGISTRATION_CLOSED", "release credential not configured");
        }
        String given = request.getHeader(RELEASE_CREDENTIAL);
        if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            log.warn("plugin_release_refused remote={} state={}", request.getRemoteAddr(), given == null ? "missing" : "mismatch");
            throw ApiException.unauthorized("RELEASE_CREDENTIAL_INVALID", "missing or invalid release credential");
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(rawBody);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("RELEASE_BODY_INVALID", "release body is not valid JSON");
        }
        List<PluginLifecycleService.DesiredPlugin> desired = new ArrayList<>();
        for (JsonNode p : body.path("plugins")) {
            desired.add(new PluginLifecycleService.DesiredPlugin(p.path("code").asText(null), p.path("version").asText(null),
                p.path("baseUrl").asText(null), p.get("manifest")));
        }
        var outcomes = lifecycle.reconcileRelease(body.path("release").asText(null), body.get("provenance"), desired,
            body.path("suspendMissing").asBoolean(false));
        return Map.of("release", body.path("release").asText(), "plugins", outcomes);
    }
}
