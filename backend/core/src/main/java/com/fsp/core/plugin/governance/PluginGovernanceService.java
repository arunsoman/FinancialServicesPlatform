package com.fsp.core.plugin.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalRequestRepository;
import com.fsp.core.common.ApiException;
import com.fsp.core.plugin.installation.PluginInstallation;
import com.fsp.core.plugin.installation.PluginInstallationGate;
import com.fsp.core.plugin.installation.PluginInstallationGateRepository;
import com.fsp.core.plugin.installation.PluginInstallationRepository;
import com.fsp.core.plugin.registry.PluginType;
import com.fsp.core.plugin.registry.PluginTypeRepository;
import com.fsp.core.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Plugin governance reads (GAP-4). Registration and installation stay where they were
 * ({@code PluginRegistryService}, {@code PluginInstallationService}) and stay four-eyes; this
 * service only makes their state visible: types with their open approval, installations with
 * their readiness gates (CLIENT_ACTIVE / PERMISSIONS_SEEDED / SECRETS_PROVISIONED / …).
 */
@Slf4j
@Service
public class PluginGovernanceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PluginTypeRepository types;
    private final PluginInstallationRepository installations;
    private final PluginInstallationGateRepository gates;
    private final ApprovalRequestRepository approvals;
    private final TenantRepository tenants;

    public PluginGovernanceService(PluginTypeRepository types, PluginInstallationRepository installations,
                                   PluginInstallationGateRepository gates, ApprovalRequestRepository approvals,
                                   TenantRepository tenants) {
        this.types = types;
        this.installations = installations;
        this.gates = gates;
        this.approvals = approvals;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public List<PluginGovernanceDtos.TypeView> types() {
        List<PluginGovernanceDtos.TypeView> out = new ArrayList<>();
        types.findAll().forEach(t -> out.add(typeView(t)));
        out.sort(Comparator.comparing(PluginGovernanceDtos.TypeView::code)
            .thenComparing(PluginGovernanceDtos.TypeView::version));
        return out;
    }

    @Transactional(readOnly = true)
    public List<PluginGovernanceDtos.InstallationView> installations(UUID tenantId) {
        if (!tenants.existsById(tenantId)) throw ApiException.notFound("TENANT_NOT_FOUND", "Organization not found");
        return installations.findByTenantId(tenantId).stream()
            .sorted(Comparator.comparing((PluginInstallation i) -> i.pluginCode))
            .map(this::installationView)
            .toList();
    }

    private PluginGovernanceDtos.TypeView typeView(PluginType t) {
        String name = t.code;
        int ops = 0;
        TreeSet<String> perms = new TreeSet<>();
        try {
            JsonNode m = MAPPER.readTree(t.manifest);
            name = m.path("name").asText(t.code);
            for (JsonNode op : m.path("operations")) {
                ops++;
                String p = op.path("permission").asText();
                if (!p.isBlank()) perms.add(p);
            }
        } catch (Exception e) {
            log.warn("plugin_manifest_unreadable code={} version={}", t.code, t.version);
        }
        return new PluginGovernanceDtos.TypeView(t.id, t.code, t.version, name, t.baseUrl, t.status, t.manifestHash,
            ops, List.copyOf(perms), t.registeredAt, t.approvedAt, pending(t.id, ApprovalRequest.OpType.PLUGIN_REGISTER));
    }

    private PluginGovernanceDtos.InstallationView installationView(PluginInstallation i) {
        List<PluginGovernanceDtos.GateView> gs = gates.findByInstallationId(i.id).stream()
            .sorted(Comparator.comparing((PluginInstallationGate g) -> g.gate))
            .map(g -> new PluginGovernanceDtos.GateView(g.gate, g.status, g.updatedAt))
            .toList();
        boolean ready = "ACTIVE".equals(i.status) && !gs.isEmpty() && gs.stream().allMatch(g -> "ACTIVE".equals(g.status()));
        return new PluginGovernanceDtos.InstallationView(i.id, i.tenantId, i.pluginCode, i.pluginVersion, i.status,
            i.inheritanceOrigin, gs, ready, i.createdAt, i.updatedAt, pending(i.id, ApprovalRequest.OpType.PLUGIN_INSTALL));
    }

    private UUID pending(UUID target, String opType) {
        return approvals.findByTargetResourceIdAndStatus(target, ApprovalRequest.Status.PENDING_CHECKER).stream()
            .filter(a -> opType.equals(a.operationType)).map(a -> a.id).findFirst().orElse(null);
    }
}
