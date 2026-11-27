package com.fsp.plugin.kyc.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The checker flag lives in core (persons.is_checker). The plugin asks core as the acting person;
 * reading core's tables from the plugin database failed with "bad SQL grammar" (no persons table
 * there) and turned every case view into a 500 — the checker could never see Approve.
 */
class KycCheckerLookupTest {

    private final CoreClient core = Mockito.mock(CoreClient.class);
    private final KycCaseService service = new KycCaseService(null, null, null, null, null, null, null, core);

    @Test
    void theCheckerFlagComesFromCoreAsTheActingPerson() {
        when(core.me("assertion-checker")).thenReturn(Map.of("checker", true, "permissions", List.of("kyc.approve")));
        when(core.me("assertion-maker")).thenReturn(Map.of("checker", false));
        assertThat(service.isChecker("assertion-checker")).isTrue();
        assertThat(service.isChecker("assertion-maker")).isFalse();
    }

    @Test
    void anUnreachableCoreNeverGrantsDecisionControls() {
        when(core.me("a")).thenThrow(new IllegalStateException("core down"));
        assertThat(service.isChecker("a")).isFalse();
        assertThat(service.isChecker(null)).isFalse();
        verify(core, never()).me(null);
    }

    @Test
    void pluginCodeNeverQueriesCoreTables() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    try {
                        return Files.readString(p).matches("(?s).*\\b(FROM|JOIN|UPDATE|INTO)\\s+persons\\b.*");
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .map(Path::toString).toList();
            assertThat(offenders).as("plugins never read core tables (AGENTS §4) — ask core over its API").isEmpty();
        }
    }
}
