package com.fsp.core.plugin.governance;

import com.fsp.core.common.Actor;
import com.fsp.core.common.FspOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Plugin governance reads (GAP-4). Permission {@code plugin.manage} is enforced by the operation
 * interceptor against the path tenant (types: the caller's active tenant — the platform root).
 */
@RestController
public class PluginGovernanceController {

    private final PluginGovernanceService service;

    public PluginGovernanceController(PluginGovernanceService service) {
        this.service = service;
    }

    @FspOperation(id = "plugin.type.list", component = "platform", permission = "plugin.manage",
        risk = FspOperation.OperationRisk.READ, title = "List plugin types", actors = {Actor.ActorType.PERSON})
    @GetMapping("/v1/platform/plugins")
    public List<PluginGovernanceDtos.TypeView> types() {
        return service.types();
    }

    @FspOperation(id = "plugin.installation.list", component = "platform", permission = "plugin.manage",
        risk = FspOperation.OperationRisk.READ, title = "List an organization's plugin installations",
        actors = {Actor.ActorType.PERSON})
    @GetMapping("/v1/tenants/{tenantId}/plugins")
    public List<PluginGovernanceDtos.InstallationView> installations(@PathVariable("tenantId") UUID tenantId) {
        return service.installations(tenantId);
    }
}
