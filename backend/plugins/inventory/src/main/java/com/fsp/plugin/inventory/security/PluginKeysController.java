package com.fsp.plugin.inventory.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The PUBLIC key the platform registers for this plugin's client (oauth_clients.jwks).
 * Registration copies it once, under four-eyes; the IdP never fetches it at runtime.
 */
@RestController
public class PluginKeysController {

    private final MachineCredentials credentials;

    public PluginKeysController(MachineCredentials credentials) {
        this.credentials = credentials;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return credentials.registrationJwks();
    }
}
