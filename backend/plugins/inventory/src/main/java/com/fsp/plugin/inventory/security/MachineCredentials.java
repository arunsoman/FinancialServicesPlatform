package com.fsp.plugin.inventory.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.shared.ActorAssertion;
import com.fsp.shared.Es256;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The plugin's machine identity (plan §4.2 / FAPI 2.0 §5.3.3):
 *  - client authentication = private_key_jwt signed with the CLIENT key (registered in
 *    oauth_clients.jwks); aud = the IdP issuer string; single-use jti; 60 s lifetime;
 *  - sender constraint = DPoP with a separate DPOP key: one fresh proof per request
 *    (htm, htu, iat, jti, and ath once a token is presented);
 *  - grant = client_credentials only, no refresh token; the token is re-obtained when it is
 *    within 30 s of expiry. Everything goes through the gateway (the sole public origin).
 * Keys are PEM files (dev: generated on first boot, gitignored like the rest of the dev PKI).
 */
@Slf4j
@Component
public class MachineCredentials {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String clientId;
    private final String issuer;
    private final String gatewayUrl;
    private final KeyPair clientKey;
    private final KeyPair dpopKey;
    private final Map<String, Object> dpopJwk;
    private final RestClient rest = RestClient.create();

    private volatile String token;
    private volatile long tokenExp;

    public MachineCredentials(@Value("${fsp.plugin.client-id}") String clientId,
                              @Value("${fsp.idp.issuer}") String issuer,
                              @Value("${fsp.core.gatewayUrl}") String gatewayUrl,
                              @Value("${fsp.plugin.client-key}") String clientKeyPath,
                              @Value("${fsp.plugin.dpop-key}") String dpopKeyPath,
                              @Value("${fsp.plugin.create-keys:true}") boolean createKeys) {
        this.clientId = clientId;
        this.issuer = issuer;
        this.gatewayUrl = gatewayUrl;
        this.clientKey = Es256.loadOrCreate(Path.of(clientKeyPath), createKeys);
        this.dpopKey = Es256.loadOrCreate(Path.of(dpopKeyPath), createKeys);
        this.dpopJwk = ActorAssertion.jwkOf((ECPublicKey) dpopKey.getPublic());
        log.info("plugin_machine_identity client={} clientKeyKid={}", clientId, clientKid());
    }

    public String clientId() { return clientId; }

    /** Public JWKS to register for this client (client-auth key only — never private parts). */
    public Map<String, Object> registrationJwks() {
        Map<String, Object> k = new LinkedHashMap<>(ActorAssertion.jwkOf((ECPublicKey) clientKey.getPublic()));
        k.put("use", "sig");
        k.put("alg", "ES256");
        k.put("kid", clientKid());
        return Map.of("keys", List.of(k));
    }

    private String clientKid() {
        return com.fsp.shared.JwtService.jwkThumbprint(
            MAPPER.valueToTree(ActorAssertion.jwkOf((ECPublicKey) clientKey.getPublic())));
    }

    /** Authorization header value: "DPoP <token>" (RFC 9449 §7.1). */
    public synchronized String authorization() {
        long now = Instant.now().getEpochSecond();
        if (token == null || now > tokenExp - 30) {
            fetchToken();
        }
        return "DPoP " + token;
    }

    /** A fresh DPoP proof for one request; binds the current token via ath. */
    public String proof(String method, String url) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "dpop+jwt");
        header.put("alg", "ES256");
        header.put("jwk", dpopJwk);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("jti", UUID.randomUUID().toString());
        p.put("htm", method.toUpperCase());
        p.put("htu", url);
        p.put("iat", Instant.now().getEpochSecond());
        if (token != null) p.put("ath", Es256.sha256B64(token));
        return Es256.sign(header, p, (ECPrivateKey) dpopKey.getPrivate());
    }

    private void fetchToken() {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("alg", "ES256");
        h.put("typ", "JWT");
        h.put("kid", clientKid());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientId);
        claims.put("sub", clientId);
        claims.put("aud", issuer); // FAPI 2.0: the issuer identifier as a STRING
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now);
        claims.put("exp", now + 60);
        String assertion = Es256.sign(h, claims, (ECPrivateKey) clientKey.getPrivate());

        // DPoP proof for the token request: no ath (no token yet); htu = the AS token endpoint
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("typ", "dpop+jwt");
        ph.put("alg", "ES256");
        ph.put("jwk", dpopJwk);
        Map<String, Object> pp = new LinkedHashMap<>();
        pp.put("jti", UUID.randomUUID().toString());
        pp.put("htm", "POST");
        pp.put("htu", issuer + "/idp/token");
        pp.put("iat", now);
        String proof = Es256.sign(ph, pp, (ECPrivateKey) dpopKey.getPrivate());

        Map<String, String> body = Map.of(
            "grant_type", "client_credentials",
            "client_id", clientId,
            "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            "client_assertion", assertion);
        try {
            String res = rest.post().uri(gatewayUrl + "/idp/token")
                .contentType(MediaType.APPLICATION_JSON)
                .header("DPoP", proof)
                .body(body)
                .retrieve().body(String.class);
            JsonNode n = MAPPER.readTree(res);
            String t = n.path("access_token").asText(n.path("accessToken").asText(""));
            long ttl = n.path("expires_in").asLong(n.path("expiresIn").asLong(300));
            if (t.isBlank()) throw new IllegalStateException("token response without access_token");
            token = t;
            tokenExp = now + ttl;
            log.info("plugin_machine_token_obtained client={} expiresIn={}", clientId, ttl);
        } catch (Exception e) {
            log.warn("plugin_machine_token_failed client={} detail={}", clientId, e.getMessage());
            throw new IllegalStateException("machine sign-in failed: " + e.getMessage(), e);
        }
    }
}
