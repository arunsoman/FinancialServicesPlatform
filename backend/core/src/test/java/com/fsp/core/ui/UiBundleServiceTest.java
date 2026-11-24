package com.fsp.core.ui;

import com.fsp.core.config.FspProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Signed-portal-delivery-plan S1: bundle assembly, sign-on-publish payload, catalog filter. */
class UiBundleServiceTest {

    @TempDir
    static Path dir;
    static UiSigner signer;

    @BeforeAll
    static void initSigner() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();
        Files.writeString(dir.resolve("b-signing.pem"), pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(dir.resolve("b-signing-pub.pem"), pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        FspProperties props = new FspProperties();
        props.getUi().setSigningKeyPath(dir.resolve("b-signing.pem").toString());
        signer = new UiSigner(props);
    }

    private static String pem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    private static UiLayout published(String portal) {
        UiLayout l = new UiLayout();
        l.id = UUID.randomUUID();
        l.portal = portal;
        l.status = UiLayout.Status.PUBLISHED;
        l.layoutJson = "{\"portal\":\"" + portal + "\",\"menus\":[{\"id\":\"kyc\"}]}";
        l.updatedAt = Instant.parse("2026-11-18T03:44:38Z");
        return l;
    }

    private static UiWidget widget(String id, String portalsJson, String sha) {
        UiWidget w = new UiWidget();
        w.id = id;
        w.version = "1.0.0";
        w.bundle = "widgets/" + id;
        w.portals = portalsJson;
        w.requires = "[\"kyc.review\"]";
        w.bundleSha256 = sha;
        w.status = "ACTIVE";
        return w;
    }

    @Test
    void generatesSignedCurrentBundleWithDigestAndJws() {
        UiLayoutRepository layouts = mock(UiLayoutRepository.class);
        UiWidgetRepository widgets = mock(UiWidgetRepository.class);
        UiBundleRepository bundles = mock(UiBundleRepository.class);
        when(layouts.findFirstByPortalAndStatus("admin", UiLayout.Status.PUBLISHED))
                .thenReturn(Optional.of(published("admin")));
        when(widgets.findAllByOrderByIdAsc()).thenReturn(List.of(
                widget("kyc.orgs", "[\"admin\"]", null)));
        UiBundleService svc = new UiBundleService(layouts, widgets, bundles, signer);

        UiBundle row = svc.generate("admin");

        assertThat(row).isNotNull();
        assertThat(row.current).isTrue();
        assertThat(row.digest).startsWith("sha256:").hasSize("sha256:".length() + 64);
        assertThat(row.jws.split("\\.")).hasSize(3);
        assertThat(row.kid).isEqualTo(signer.kid());
        verify(bundles).markHistorical("admin");
        verify(bundles).save(any(UiBundle.class));
        // payload embeds the widget with the unregistered-hash fallback
        assertThat(row.payload).contains("\"bundleHash\":\"sha256:unregistered\"")
                .contains("\"id\":\"kyc.orgs\"").contains("\"requires\":[\"kyc.review\"]");
        // payload is canonical (sorted, minified)
        assertThat(row.payload).isEqualTo(signer.canonicalize(row.payload));
    }

    @Test
    void filtersWidgetsByPortalAndSkipsForeignAndHashedBundleIsEmbedded() {
        UiLayoutRepository layouts = mock(UiLayoutRepository.class);
        UiWidgetRepository widgets = mock(UiWidgetRepository.class);
        UiBundleRepository bundles = mock(UiBundleRepository.class);
        when(layouts.findFirstByPortalAndStatus("admin", UiLayout.Status.PUBLISHED))
                .thenReturn(Optional.of(published("admin")));
        String sha = "sha256:" + "ab".repeat(32);
        when(widgets.findAllByOrderByIdAsc()).thenReturn(List.of(
                widget("kyc.orgs", "[\"admin\"]", sha),
                widget("pay.batch", "[\"org\"]", null),
                widget("dead.wid", "[\"admin\"]", null) /* not ACTIVE below */));
        // make dead.wid non-ACTIVE to test the status filter
        UiWidget dead = widget("dead.wid", "[\"admin\"]", null);
        dead.status = "ARCHIVED";
        when(widgets.findAllByOrderByIdAsc()).thenReturn(List.of(
                widget("kyc.orgs", "[\"admin\"]", sha), widget("pay.batch", "[\"org\"]", null), dead));
        UiBundleService svc = new UiBundleService(layouts, widgets, bundles, signer);

        UiBundle row = svc.generate("admin");

        assertThat(row.payload).contains(sha).doesNotContain("pay.batch").doesNotContain("dead.wid");
        assertThat(row.payload).doesNotContain("sha256:unregistered");
    }

    @Test
    void signedPayloadMatchesTheReloadedRowDespiteDatabaseTimestampPrecision() {
        // the publisher signs the in-memory row (nanosecond Instant); Postgres keeps microseconds,
        // so the checkpoint / boot sync re-assemble from a row that lost the last digits
        UiLayout inMemory = published("admin");
        inMemory.updatedAt = java.time.Instant.parse("2026-11-23T02:01:51.947428223Z");
        UiLayout reloaded = published("admin");
        reloaded.id = inMemory.id;
        reloaded.layoutJson = inMemory.layoutJson;
        reloaded.updatedAt = java.time.Instant.parse("2026-11-23T02:01:51.947428Z");
        UiLayoutRepository layouts = mock(UiLayoutRepository.class);
        UiWidgetRepository widgets = mock(UiWidgetRepository.class);
        when(widgets.findAllByOrderByIdAsc()).thenReturn(List.of());
        when(layouts.findFirstByPortalAndStatus("admin", UiLayout.Status.PUBLISHED))
                .thenReturn(Optional.of(inMemory), Optional.of(reloaded));
        UiBundleService svc = new UiBundleService(layouts, widgets, mock(UiBundleRepository.class), signer);

        UiBundle signed = svc.generate("admin");

        assertThat(signer.digest(svc.canonicalPayloadFor("admin"))).isEqualTo(signed.digest);
    }

    @Test
    void noPublishedLayoutSkipsGeneration() {
        UiLayoutRepository layouts = mock(UiLayoutRepository.class);
        UiWidgetRepository widgets = mock(UiWidgetRepository.class);
        UiBundleRepository bundles = mock(UiBundleRepository.class);
        when(layouts.findFirstByPortalAndStatus("admin", UiLayout.Status.PUBLISHED))
                .thenReturn(Optional.<UiLayout>empty());
        UiBundleService svc = new UiBundleService(layouts, widgets, bundles, signer);

        assertThat(svc.generate("admin")).isNull();
        verify(bundles, never()).save(any());
    }

    @Test
    void currentReturnsViewOrNotFound() {
        UiBundleRepository bundles = mock(UiBundleRepository.class);
        when(bundles.findByPortalAndCurrentTrue("admin")).thenReturn(Optional.<UiBundle>empty());
        UiBundleService svc = new UiBundleService(mock(UiLayoutRepository.class),
                mock(UiWidgetRepository.class), bundles, signer);

        assertThatThrownBy(() -> svc.current("admin"))
                .isInstanceOf(com.fsp.core.common.ApiException.class)
                .satisfies(e -> assertThat(((com.fsp.core.common.ApiException) e).getCode())
                        .isEqualTo("UI_BUNDLE_NOT_FOUND"));
    }

    @Test
    void regenerateSkipsPortalsWithoutPublishedLayout() {
        UiLayoutRepository layouts = mock(UiLayoutRepository.class);
        UiWidgetRepository widgets = mock(UiWidgetRepository.class);
        UiBundleRepository bundles = mock(UiBundleRepository.class);
        when(layouts.findFirstByPortalAndStatus(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.<UiLayout>empty());
        UiBundleService svc = new UiBundleService(layouts, widgets, bundles, signer);

        svc.regenerateForWidgets(List.of("admin", "org"));

        verify(bundles, never()).save(any());
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bundles, never()).markHistorical(captor.capture());
    }
}
