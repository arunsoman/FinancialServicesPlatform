package com.fsp.core.plugin.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.outbox.OutboxService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * Plugins ship as code (docs/plugin-registration.md): a release registers what it ships, names the
 * people behind it, never lifts a person's four-eyes suspension, and suspends what it no longer ships.
 */
class PluginLifecycleServiceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final PluginTypeRepository types = mock(PluginTypeRepository.class);
    private final PluginRegistryService registry = mock(PluginRegistryService.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final com.fsp.core.plugin.installation.PluginAutoInstaller installer =
        mock(com.fsp.core.plugin.installation.PluginAutoInstaller.class);
    private final PluginLifecycleService service = new PluginLifecycleService(types, registry, approvals,
        mock(AuditService.class), mock(OutboxService.class), installer);
    private final List<PluginType> saved = new ArrayList<>();

    PluginLifecycleServiceTest() {
        when(types.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
    }

    private static JsonNode manifest(String version, String x) throws Exception {
        return M.readTree("{\"code\":\"kyc\",\"version\":\"" + version + "\",\"client\":{\"jwks\":{\"keys\":[{\"kty\":\"EC\",\"x\":\"" + x + "\"}]}}}");
    }

    private static JsonNode prov() throws Exception {
        return M.readTree("{\"commit\":\"abc123\",\"pr\":7,\"author\":\"dev-a\",\"reviewers\":[\"dev-b\"]}");
    }

    private PluginType row(String version, String status, JsonNode manifest) {
        return new PluginType(UUID.randomUUID(), "kyc", version, "http://plugin-kyc:8083", manifest.toString(), "h", status, UUID.randomUUID());
    }

    private PluginLifecycleService.DesiredPlugin desired(String version, JsonNode m) {
        return new PluginLifecycleService.DesiredPlugin("kyc", version, "http://plugin-kyc:8083", m);
    }

    @Test
    void registersAShippedPluginWithNoFspMakerAndTheReviewersAsProvenance() throws Exception {
        when(types.findByCode("kyc")).thenReturn(List.of());
        var out = service.reconcileRelease("stage-2026.11.25-9", prov(), List.of(desired("1.1.0", manifest("1.1.0", "a"))), false);

        assertEquals("registered", out.get(0).action());
        PluginType t = saved.get(saved.size() - 1);
        assertEquals("REGISTERED", t.status);
        assertEquals("RELEASE", t.registeredVia);
        assertNull(t.registeredBy, "a release registration has no FSP maker — and never a machine one");
        assertEquals("stage-2026.11.25-9", t.releaseRef);
        assertEquals(prov().toString(), t.provenance);
        verify(registry).provisionMachineClient(t, PluginLifecycleService.PLATFORM_ROOT, null);
    }

    // ---- easy install (AGENTS.md §2.1): shipping a plugin registers AND installs it, one step

    @Test
    void aShippedPluginIsInstalledOnThePlatformRootInTheSameStep() throws Exception {
        when(types.findByCode("kyc")).thenReturn(List.of());
        when(installer.ensureOnPlatformRoot(eq("kyc"), eq("1.1.0"), isNull(), any())).thenReturn("installed");
        var out = service.reconcileRelease("stage-5", prov(), List.of(desired("1.1.0", manifest("1.1.0", "a"))), false);
        assertEquals("registered", out.get(0).action());
        assertEquals("installed", out.get(0).installation());
        verify(approvals, never()).open(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anAlreadyRegisteredPluginThatWasNeverInstalledGetsInstalledByTheNextDeploy() throws Exception {
        // staging 2026-11-25: kyc was in_sync but installed nowhere — Customers said "not ready" forever
        JsonNode m = manifest("1.1.0", "a");
        when(types.findByCode("kyc")).thenReturn(List.of(row("1.1.0", "REGISTERED", m)));
        when(installer.ensureOnPlatformRoot(eq("kyc"), eq("1.1.0"), isNull(), any())).thenReturn("installed");
        var out = service.reconcileRelease("stage-6", prov(), List.of(desired("1.1.0", m)), false);
        assertEquals("in_sync", out.get(0).action());
        assertEquals("installed", out.get(0).installation());
    }

    @Test
    void aPersonSuspendedPluginIsNotInstalledByADeploy() throws Exception {
        PluginType susp = row("1.1.0", "SUSPENDED", manifest("1.1.0", "a"));
        susp.suspendedReason = "PERSON";
        when(types.findByCode("kyc")).thenReturn(List.of(susp));
        service.reconcileRelease("stage-7", prov(), List.of(desired("1.1.0", manifest("1.1.0", "a"))), false);
        verify(installer, never()).ensureOnPlatformRoot(any(), any(), any(), any());
    }

    @Test
    void sameManifestIsANoOp() throws Exception {
        JsonNode m = manifest("1.1.0", "a");
        when(types.findByCode("kyc")).thenReturn(List.of(row("1.1.0", "REGISTERED", m)));
        var out = service.reconcileRelease("stage-1", prov(), List.of(desired("1.1.0", m)), false);
        assertEquals("in_sync", out.get(0).action());
        verify(types, never()).save(any());
    }

    @Test
    void newVersionSupersedesTheOldOne() throws Exception {
        PluginType old = row("1.0.0", "REGISTERED", manifest("1.0.0", "a"));
        when(types.findByCode("kyc")).thenReturn(List.of(old));
        service.reconcileRelease("stage-2", prov(), List.of(desired("1.1.0", manifest("1.1.0", "a"))), false);
        assertEquals("DEACTIVATED", old.status);
    }

    @Test
    void rotatedKeyUpdatesTheSameVersionInPlaceAndWithdrawsAPendingPersonRequest() throws Exception {
        PluginType pending = row("1.1.0", "PENDING_APPROVAL", manifest("1.1.0", "old"));
        when(types.findByCode("kyc")).thenReturn(List.of(pending));
        var out = service.reconcileRelease("stage-3", prov(), List.of(desired("1.1.0", manifest("1.1.0", "new"))), false);
        assertEquals("updated", out.get(0).action());
        assertEquals("REGISTERED", pending.status);
        assertEquals(manifest("1.1.0", "new").toString(), pending.manifest);
        verify(approvals).withdrawPendingFor(eq(ApprovalRequest.OpType.PLUGIN_REGISTER), eq(pending.id), any());
    }

    @Test
    void aPersonsSuspensionIsNeverLiftedByADeploy() throws Exception {
        PluginType susp = row("1.1.0", "SUSPENDED", manifest("1.1.0", "a"));
        susp.suspendedReason = "PERSON";
        when(types.findByCode("kyc")).thenReturn(List.of(susp));
        var out = service.reconcileRelease("stage-4", prov(), List.of(desired("1.2.0", manifest("1.2.0", "a"))), false);
        assertEquals("held_suspended", out.get(0).action());
        assertEquals("SUSPENDED", susp.status);
        verify(types, never()).save(any());
    }

    @Test
    void aPluginNoLongerShippedIsSuspendedNotDeleted() throws Exception {
        PluginType inv = new PluginType(UUID.randomUUID(), "inventory", "1.0.1", "http://plugin-inventory:8084",
            "{}", "h", "REGISTERED", UUID.randomUUID());
        when(types.findByCode("kyc")).thenReturn(List.of());
        when(types.findByStatus("REGISTERED")).thenReturn(List.of(inv));
        var out = service.reconcileRelease("stage-5", prov(), List.of(desired("1.1.0", manifest("1.1.0", "a"))), true);
        assertEquals("suspended_not_shipped", out.get(1).action());
        assertEquals("SUSPENDED", inv.status);
        assertEquals("RELEASE", inv.suspendedReason);
        verify(registry).setMachineClientStatus("inventory", "SUSPENDED");
    }

    @Test
    void refusesWithoutProvenanceOrWithAForeignBaseUrl() throws Exception {
        var d = desired("1.1.0", manifest("1.1.0", "a"));
        assertThrows(ApiException.class, () -> service.reconcileRelease("stage-6", null, List.of(d), false));
        var evil = new PluginLifecycleService.DesiredPlugin("kyc", "1.1.0", "https://attacker.example", manifest("1.1.0", "a"));
        assertThrows(ApiException.class, () -> service.reconcileRelease("stage-6", prov(), List.of(evil), false));
        var mismatch = desired("1.2.0", manifest("1.1.0", "a"));
        assertThrows(ApiException.class, () -> service.reconcileRelease("stage-6", prov(), List.of(mismatch), false));
    }

    @Test
    void suspendAndResumeGoThroughFourEyesApprovals() throws Exception {
        PluginType reg = row("1.1.0", "REGISTERED", manifest("1.1.0", "a"));
        Actor maker = new Actor(UUID.randomUUID(), "platform-admin", PluginLifecycleService.PLATFORM_ROOT);
        Actor checker = new Actor(UUID.randomUUID(), "admin-checker", PluginLifecycleService.PLATFORM_ROOT);
        when(types.findByCodeAndStatus("kyc", "REGISTERED")).thenReturn(List.of(reg));
        ApprovalRequest ar = new ApprovalRequest();
        ar.id = UUID.randomUUID();
        when(approvals.open(any(), eq(ApprovalRequest.OpType.PLUGIN_SUSPEND), eq(reg.id), eq(maker), eq(maker.personId()), any(), any()))
            .thenReturn(ar);

        service.submitSuspend("kyc", "incident 42", maker);
        assertEquals("REGISTERED", reg.status, "submitting changes nothing until a checker approves");

        when(types.findById(reg.id)).thenReturn(java.util.Optional.of(reg));
        service.applySuspend(reg.id, checker);
        assertEquals("SUSPENDED", reg.status);
        assertEquals("PERSON", reg.suspendedReason);
        assertEquals(checker.personId(), reg.suspendedBy);
        verify(registry).setMachineClientStatus("kyc", "SUSPENDED");

        when(types.findByCodeAndStatus("kyc", "REGISTERED")).thenReturn(List.of());
        service.applyResume(reg.id, checker);
        assertEquals("REGISTERED", reg.status);
        assertNull(reg.suspendedReason);
        verify(registry).setMachineClientStatus("kyc", "ACTIVE");
    }
}
