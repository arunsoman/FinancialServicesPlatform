package com.fsp.core.ui;

import com.fsp.core.audit.AuditService;
import com.fsp.core.config.FspProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Menus ship with the code: the seed writes PUBLISHED layouts on deploy and core signs them at
 * boot — no manual publish step in any environment. A plain restart signs nothing.
 */
class UiBundleBootSyncTest {

    @TempDir
    static Path dir;
    static UiSigner signer;

    UiLayoutRepository layouts;
    UiBundleRepository bundles;
    UiBundleService assembly;
    AuditService audit;
    UiBundleBootSync sync;
    UiLayout admin;

    static final String PAYLOAD = "{\"portal\":\"admin\",\"menus\":[]}";

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();
        Files.writeString(dir.resolve("bs-signing.pem"), pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(dir.resolve("bs-signing-pub.pem"), pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        FspProperties props = new FspProperties();
        props.getUi().setSigningKeyPath(dir.resolve("bs-signing.pem").toString());
        props.getUi().setSigningKid("bs-kid-1");
        signer = new UiSigner(props);
    }

    @BeforeEach
    void wire() {
        layouts = mock(UiLayoutRepository.class);
        bundles = mock(UiBundleRepository.class);
        assembly = mock(UiBundleService.class);
        audit = mock(AuditService.class);
        sync = new UiBundleBootSync(layouts, bundles, assembly, signer, audit);
        admin = new UiLayout();
        admin.id = UUID.randomUUID();
        admin.portal = "admin";
        admin.status = UiLayout.Status.PUBLISHED;
        when(layouts.findAllByStatus(UiLayout.Status.PUBLISHED)).thenReturn(List.of(admin));
        when(assembly.canonicalPayloadFor("admin")).thenReturn(PAYLOAD);
        when(assembly.generate("admin")).thenAnswer(inv -> signed(PAYLOAD));
    }

    @Test
    void freshInstallSignsTheSeededMenuAtBoot() {
        when(bundles.findByPortalAndCurrentTrue("admin")).thenReturn(Optional.empty());

        assertThat(sync.sync()).isEqualTo(1);

        verify(assembly).generate("admin");
        verify(audit).record(isNull(), isNull(), isNull(), isNull(), eq(UiBundleBootSync.AUDIT_ACTION),
            eq("UI_BUNDLE"), anyString(), isNull(), eq(signer.digest(PAYLOAD)), eq(false));
    }

    @Test
    void changedSeedReSignsTheStaleBundle() {
        when(bundles.findByPortalAndCurrentTrue("admin")).thenReturn(Optional.of(bundle("sha256:old")));

        assertThat(sync.sync()).isEqualTo(1);

        verify(assembly).generate("admin");
        verify(audit).record(isNull(), isNull(), isNull(), isNull(), eq(UiBundleBootSync.AUDIT_ACTION),
            eq("UI_BUNDLE"), anyString(), eq("sha256:old"), eq(signer.digest(PAYLOAD)), eq(false));
    }

    @Test
    void plainRestartSignsNothing() {
        when(bundles.findByPortalAndCurrentTrue("admin")).thenReturn(Optional.of(signed(PAYLOAD)));

        assertThat(sync.sync()).isZero();

        verify(assembly, never()).generate(any());
        verify(audit, never()).record(any(), any(), any(), any(), anyString(), anyString(), anyString(),
            any(), any(), anyBoolean());
    }

    @Test
    void reKeyedEnvironmentReSignsAnUnchangedMenu() {
        UiBundle oldKey = signed(PAYLOAD);
        oldKey.jws = oldKey.jws.substring(0, oldKey.jws.lastIndexOf('.') + 1) + "c2lnbmVkLWJ5LWFub3RoZXIta2V5";
        when(bundles.findByPortalAndCurrentTrue("admin")).thenReturn(Optional.of(oldKey));

        assertThat(sync.sync()).isEqualTo(1);
        verify(assembly).generate("admin");
    }

    @Test
    void oneBrokenPortalDoesNotStopTheOthersOrTheBoot() {
        UiLayout merchant = new UiLayout();
        merchant.portal = "merchant";
        merchant.status = UiLayout.Status.PUBLISHED;
        when(layouts.findAllByStatus(UiLayout.Status.PUBLISHED)).thenReturn(List.of(merchant, admin));
        when(assembly.canonicalPayloadFor("merchant")).thenThrow(new IllegalStateException("corrupt layout"));
        when(bundles.findByPortalAndCurrentTrue("admin")).thenReturn(Optional.empty());

        assertThat(sync.sync()).isEqualTo(1);
        verify(assembly).generate("admin");
    }

    private static UiBundle signed(String payload) {
        UiBundle b = bundle(signer.digest(payload));
        b.payload = payload;
        b.jws = signer.sign(payload);
        return b;
    }

    private static UiBundle bundle(String digest) {
        UiBundle b = new UiBundle();
        b.id = UUID.randomUUID();
        b.portal = "admin";
        b.current = true;
        b.digest = digest;
        return b;
    }

    private static String pem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }
}
