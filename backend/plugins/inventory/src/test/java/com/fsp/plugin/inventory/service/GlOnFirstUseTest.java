package com.fsp.plugin.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.plugin.inventory.api.ApiProblemException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Option A (owner's decision 2026-11-25): inventory is installed once on the platform root (easy
 * install) and inherited by every organization. The root has no ledger book, so installing must not
 * need one; each organization's GL rows are created in ITS OWN book the first time it needs them.
 */
class GlOnFirstUseTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID BOOK = UUID.randomUUID();

    private final CoreClient core = mock(CoreClient.class);
    private final GlBookResolver resolver = new GlBookResolver(core);
    private final ReadinessReporter reporter = new ReadinessReporter(core, resolver, "inventory");

    private static JsonNode json(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JsonNode book() {
        return json("[{\"id\":\"" + BOOK + "\",\"status\":\"ACTIVE\",\"currency\":\"NAD\"}]");
    }

    private static JsonNode account(int code) {
        return json("[{\"id\":\"" + UUID.randomUUID() + "\",\"code\":" + code + ",\"name\":\"x\",\"tbAccountId\":\"77\"}]");
    }

    @Test
    void theRootInstallationGoesReadyWithoutABook() {
        when(core.provisioning("inventory")).thenReturn(List.of(Map.of("installationId", UUID.randomUUID().toString(), "tenantId", ROOT.toString())));
        when(core.listBooks(ROOT, null)).thenReturn(json("[]"));

        reporter.report();

        verify(core, times(5)).reportReady(any(), any()); // every gate, GL_ACCOUNTS_ENSURED included
        verify(core, never()).ensureGlAccounts(any(), any(), anyList());
    }

    @Test
    void anOrganizationsAccountsAreCreatedInItsOwnBookOnFirstUse() {
        when(core.listBooks(ORG, null)).thenReturn(book());
        when(core.listGlAccounts(ORG, BOOK, null)).thenReturn(json("[]"), account(1200));

        var gl = resolver.gl(ORG, 1200);

        assertEquals(BOOK, gl.bookId());
        verify(core).ensureGlAccounts(eq(ORG), eq(BOOK), anyList());
    }

    @Test
    void accountsAlreadyThereAreNotEnsuredAgain() {
        when(core.listBooks(ORG, null)).thenReturn(book());
        when(core.listGlAccounts(ORG, BOOK, null)).thenReturn(account(1200));

        resolver.gl(ORG, 1200);

        verify(core, never()).ensureGlAccounts(any(), any(), anyList());
    }

    @Test
    void anOrganizationWithoutABookGetsAClearAnswer() {
        when(core.listBooks(ORG, null)).thenReturn(json("[]"));
        var e = assertThrows(ApiProblemException.class, () -> resolver.gl(ORG, 1200));
        assertEquals("INVENTORY_NO_BOOK", e.getCode());
    }

    @Test
    void anInstallationOnAnOrganizationWithABookStillWiresItsGlUpFront() {
        UUID inst = UUID.randomUUID();
        when(core.provisioning("inventory")).thenReturn(List.of(Map.of("installationId", inst.toString(), "tenantId", ORG.toString())));
        when(core.listBooks(ORG, null)).thenReturn(book());

        reporter.report();

        verify(core).ensureGlAccounts(eq(ORG), eq(BOOK), anyList());
        verify(core, times(5)).reportReady(eq(inst), any());
    }
}
