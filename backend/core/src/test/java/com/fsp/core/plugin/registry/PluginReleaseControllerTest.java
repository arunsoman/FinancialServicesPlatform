package com.fsp.core.plugin.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fsp.core.config.FspProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /internal/release/plugins through Spring's real request reading. The first staging deploy
 * answered 500 here: the body was declared as a Jackson 2 JsonNode, which the Jackson 3 message
 * converter cannot construct. The service tests call the service directly and never saw it.
 */
class PluginReleaseControllerTest {

    private final PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);

    private MockMvc mvc(String releaseCredential) {
        FspProperties props = new FspProperties();
        props.getSecurity().setReleaseCredential(releaseCredential);
        return MockMvcBuilders.standaloneSetup(new PluginReleaseController(lifecycle, props)).build();
    }

    private static final String BODY = """
        {"release":"stage-2026.11.25-6",
         "provenance":{"commit":"94ac0ad","pr":7,"author":"dev-a","reviewers":["dev-b"]},
         "suspendMissing":true,
         "plugins":[{"code":"kyc","version":"1.1.0","baseUrl":"http://plugin-kyc:8083",
                     "manifest":{"code":"kyc","version":"1.1.0","client":{"jwks":{"keys":[]}}}}]}
        """;

    @Test
    @SuppressWarnings("unchecked")
    void readsTheDeployBodyAndHandsItToTheLifecycle() throws Exception {
        when(lifecycle.reconcileRelease(anyString(), any(), any(), anyBoolean())).thenReturn(List.of());
        mvc("rel-secret").perform(post("/internal/release/plugins").contentType(MediaType.APPLICATION_JSON)
                .header(PluginReleaseController.RELEASE_CREDENTIAL, "rel-secret").content(BODY))
            .andExpect(status().isOk());

        ArgumentCaptor<JsonNode> prov = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<List<PluginLifecycleService.DesiredPlugin>> desired = ArgumentCaptor.forClass(List.class);
        verify(lifecycle).reconcileRelease(eq("stage-2026.11.25-6"), prov.capture(), desired.capture(), eq(true));
        assertEquals(7, prov.getValue().path("pr").asInt());
        var kyc = desired.getValue().get(0);
        assertEquals("kyc", kyc.code());
        assertEquals("http://plugin-kyc:8083", kyc.baseUrl());
        assertTrue(kyc.manifest().path("client").has("jwks"));
    }

    @Test
    void refusesAWrongReleaseCredentialBeforeReadingAnything() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(jakarta.servlet.ServletException.class, () ->
            mvc("rel-secret").perform(post("/internal/release/plugins").contentType(MediaType.APPLICATION_JSON)
                .header(PluginReleaseController.RELEASE_CREDENTIAL, "wrong").content(BODY)));
        assertEquals("RELEASE_CREDENTIAL_INVALID", ((com.fsp.core.common.ApiException) e.getCause()).getCode());
    }
}
