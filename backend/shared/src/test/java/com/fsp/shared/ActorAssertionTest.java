package com.fsp.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorAssertionTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path dir;

    private record Pair(ActorAssertion issuer, ActorAssertion verifier) {}

    private Pair pair(int maxUses) {
        KeyPair kp = Es256.loadOrCreate(dir.resolve("gw.pem"), true);
        ActorAssertion issuer = ActorAssertion.issuer((ECPrivateKey) kp.getPrivate(), (ECPublicKey) kp.getPublic());
        JsonNode jwk = M.valueToTree(((java.util.List<?>) issuer.publicJwks().get("keys")).get(0));
        ActorAssertion verifier = ActorAssertion.verifier(kid -> kid.equals(issuer.kid()) ? jwk : null, maxUses);
        return new Pair(issuer, verifier);
    }

    private static ActorAssertion.Subject subject() {
        return new ActorAssertion.Subject("11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222", "platform-admin", "fsp-bff",
            "kyc.case.create", "org.onboard_child", "22222222-2222-2222-2222-222222222222", "corr-1");
    }

    @Test
    void mintsAndVerifiesBoundToTheRequest() {
        Pair p = pair(1);
        String a = p.issuer().mint(subject(), "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        var v = p.verifier().verify(a, "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        assertThat(v.personId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(v.permission()).isEqualTo("org.onboard_child");
        assertThat(v.operationId()).isEqualTo("kyc.case.create");
    }

    @Test
    void carriesAnActAsSessionWhenThePersonActsAsATenant() {
        Pair p = pair(1);
        var acting = new ActorAssertion.Subject("11111111-1111-1111-1111-111111111111",
            "33333333-3333-3333-3333-333333333333", "platform-admin", "fsp-bff",
            "kyc.case.create", "org.onboard_child", "33333333-3333-3333-3333-333333333333", "corr-1",
            "sess-1", "22222222-2222-2222-2222-222222222222");
        String a = p.issuer().mint(acting, "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        var v = p.verifier().verify(a, "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        assertThat(v.acting()).isTrue();
        assertThat(v.tenantId()).isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(v.actSessionId()).isEqualTo("sess-1");
        assertThat(v.homeTenantId()).isEqualTo("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void anOrdinaryAssertionIsNotActing() {
        Pair p = pair(1);
        var v = p.verifier().verify(p.issuer().mint(subject(), "plugin:kyc", "POST", "/v1/plugins/kyc/cases"),
            "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        assertThat(v.acting()).isFalse();
        assertThat(v.actSessionId()).isNull();
        assertThat(v.homeTenantId()).isNull();
    }

    @Test
    void anActClaimCannotBeAddedAfterSigning() {
        Pair p = pair(1);
        String a = p.issuer().mint(subject(), "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        String[] parts = a.split("\\.");
        String forged = new String(Base64.getUrlDecoder().decode(parts[1])).replaceFirst("\\{", "{\"act\":\"sess-x\",");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes()) + "." + parts[2];
        assertThatThrownBy(() -> p.verifier().verify(tampered, "plugin:kyc", "POST", "/v1/plugins/kyc/cases"))
            .hasMessageContaining("bad signature");
    }

    @Test
    void rejectsATamperedPayload() {
        Pair p = pair(1);
        String a = p.issuer().mint(subject(), "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        String[] parts = a.split("\\.");
        String forged = new String(Base64.getUrlDecoder().decode(parts[1]))
            .replace("11111111-1111-1111-1111-111111111111", "99999999-9999-9999-9999-999999999999");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes()) + "." + parts[2];
        assertThatThrownBy(() -> p.verifier().verify(tampered, "plugin:kyc", "POST", "/v1/plugins/kyc/cases"))
            .hasMessageContaining("bad signature");
    }

    @Test
    void rejectsAnUnsignedOrForeignKeyAssertion() {
        Pair p = pair(1);
        KeyPair other = Es256.loadOrCreate(dir.resolve("other.pem"), true);
        ActorAssertion rogue = ActorAssertion.issuer((ECPrivateKey) other.getPrivate(), (ECPublicKey) other.getPublic());
        String a = rogue.mint(subject(), "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        assertThatThrownBy(() -> p.verifier().verify(a, "plugin:kyc", "POST", "/v1/plugins/kyc/cases"))
            .isInstanceOf(ActorAssertion.Rejected.class).hasMessageContaining("unknown signing key");
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"fsp-actor+jwt\"}".getBytes())
            + "." + a.split("\\.")[1] + ".";
        assertThatThrownBy(() -> p.verifier().verify(none, "plugin:kyc", null, null))
            .isInstanceOf(ActorAssertion.Rejected.class);
    }

    @Test
    void enforcesAudienceBindingAndReplay() {
        Pair p = pair(1);
        String a = p.issuer().mint(subject(), "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        assertThatThrownBy(() -> p.verifier().verify(a, "plugin:other", "POST", "/v1/plugins/kyc/cases"))
            .extracting(e -> ((ActorAssertion.Rejected) e).code()).isEqualTo("ASSERTION_BAD_AUD");
        assertThatThrownBy(() -> p.verifier().verify(a, "plugin:kyc", "GET", "/v1/plugins/kyc/cases"))
            .extracting(e -> ((ActorAssertion.Rejected) e).code()).isEqualTo("ASSERTION_BAD_HTM");
        assertThatThrownBy(() -> p.verifier().verify(a, "plugin:kyc", "POST", "/v1/plugins/kyc/cases/x"))
            .extracting(e -> ((ActorAssertion.Rejected) e).code()).isEqualTo("ASSERTION_BAD_HTU");
        p.verifier().verify(a, "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        assertThatThrownBy(() -> p.verifier().verify(a, "plugin:kyc", "POST", "/v1/plugins/kyc/cases"))
            .extracting(e -> ((ActorAssertion.Rejected) e).code()).isEqualTo("ASSERTION_REPLAY");
    }

    @Test
    void onBehalfBudgetAllowsAFewUsesThenStops() {
        Pair p = pair(3);
        String a = p.issuer().mint(subject(), "plugin:kyc", "POST", "/v1/plugins/kyc/cases");
        for (int i = 0; i < 3; i++) p.verifier().verify(a, "plugin:kyc", null, null);
        assertThatThrownBy(() -> p.verifier().verify(a, "plugin:kyc", null, null))
            .extracting(e -> ((ActorAssertion.Rejected) e).code()).isEqualTo("ASSERTION_OVER_BUDGET");
    }

    @Test
    void reloadedKeyFileYieldsTheSamePublicKey() {
        KeyPair a = Es256.loadOrCreate(dir.resolve("k.pem"), true);
        KeyPair b = Es256.loadOrCreate(dir.resolve("k.pem"), false);
        assertThat(ActorAssertion.jwkOf((ECPublicKey) b.getPublic())).isEqualTo(ActorAssertion.jwkOf((ECPublicKey) a.getPublic()));
        Map<String, Object> h = Map.of("alg", "ES256", "typ", "JWT", "jwk", ActorAssertion.jwkOf((ECPublicKey) b.getPublic()));
        String jws = Es256.sign(h, Map.of("x", 1), (ECPrivateKey) b.getPrivate());
        String[] parts = jws.split("\\.");
        assertThat(JwtService.verifyEs256(M.valueToTree(ActorAssertion.jwkOf((ECPublicKey) a.getPublic())),
            parts[0] + "." + parts[1], parts[2])).isTrue();
    }
}
