package com.fsp.core.plugin.installation;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.plugin.registry.PluginTypeRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Easy install (AGENTS.md §2.1): registering a plugin installs it on the platform root, straight
 * to PROVISIONING — no approval, nobody waits. A person's suspension is never undone by it.
 */
class PluginAutoInstallerTest {

    private final PluginInstallationRepository installations = mock(PluginInstallationRepository.class);
    private final PluginInstallationGateRepository gates = mock(PluginInstallationGateRepository.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final PluginAutoInstaller installer = new PluginAutoInstaller(installations, gates,
        mock(PluginTypeRepository.class), approvals, mock(AuditService.class), mock(OutboxService.class));
    private final List<PluginInstallation> saved = new ArrayList<>();
    private final List<PluginInstallationGate> savedGates = new ArrayList<>();

    PluginAutoInstallerTest() {
        when(installations.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        when(gates.save(any())).thenAnswer(i -> { savedGates.add(i.getArgument(0)); return i.getArgument(0); });
        when(gates.findById(any())).thenReturn(Optional.empty());
    }

    private PluginInstallation existing(UUID tenant, String status) {
        PluginInstallation i = new PluginInstallation(UUID.randomUUID(), "kyc", "1.0.0", tenant, status, "INHERIT", "DIRECT", null);
        when(installations.findByPluginCode("kyc")).thenReturn(List.of(i));
        return i;
    }

    @Test
    void installedNowhereBecomesProvisioningOnTheRootWithNoApproval() {
        when(installations.findByPluginCode("kyc")).thenReturn(List.of());
        assertEquals("installed", installer.ensureOnPlatformRoot("kyc", "1.1.0", null, "release stage-1"));
        PluginInstallation i = saved.get(saved.size() - 1);
        assertEquals(PluginAutoInstaller.PLATFORM_ROOT, i.tenantId);
        assertEquals("PROVISIONING", i.status);
        assertEquals("INHERIT", i.inheritancePolicy, "installed on the root so every organization below gets it");
        assertEquals(4, savedGates.size(), "KYC moves no money: no GL gate, or it could never go ACTIVE");
        verifyNoInteractions(approvals);
    }

    @Test
    void anInstallationOnAnotherTenantDoesNotStopTheRootInstall() {
        existing(UUID.randomUUID(), "ACTIVE");
        assertEquals("installed", installer.ensureOnPlatformRoot("kyc", "1.1.0", null, "release"));
    }

    @Test
    void activeStaysActiveAndFollowsTheShippedVersion() {
        PluginInstallation i = existing(PluginAutoInstaller.PLATFORM_ROOT, "ACTIVE");
        assertEquals("active", installer.ensureOnPlatformRoot("kyc", "1.1.0", null, "release"));
        assertEquals("ACTIVE", i.status);
        assertEquals("1.1.0", i.pluginVersion);
        assertEquals(0, savedGates.size());
    }

    @Test
    void aPersonsSuspensionIsLeftAlone() {
        PluginInstallation i = existing(PluginAutoInstaller.PLATFORM_ROOT, "SUSPENDED");
        assertEquals("held_suspended", installer.ensureOnPlatformRoot("kyc", "1.1.0", null, "release"));
        assertEquals("SUSPENDED", i.status);
        verify(installations, never()).save(any());
    }

    @Test
    void aLeftoverFourEyesRequestIsWithdrawnAndInstalledAtOnce() {
        PluginInstallation i = existing(PluginAutoInstaller.PLATFORM_ROOT, "PENDING_APPROVAL");
        assertEquals("installed", installer.ensureOnPlatformRoot("kyc", "1.1.0", null, "release"));
        assertEquals("PROVISIONING", i.status);
        verify(approvals).withdrawPendingFor(eq(ApprovalRequest.OpType.PLUGIN_INSTALL), eq(i.id), any());
    }
}
