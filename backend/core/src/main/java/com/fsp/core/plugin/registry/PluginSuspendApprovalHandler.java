package com.fsp.core.plugin.registry;

import com.fsp.core.approval.ApprovalHandler;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.common.Actor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** PLUGIN_SUSPEND approved → the plugin is out of service (gateway 503, IdP refuses its client). */
@Slf4j
@Component
public class PluginSuspendApprovalHandler implements ApprovalHandler {

    private final PluginLifecycleService lifecycle;

    public PluginSuspendApprovalHandler(@org.springframework.context.annotation.Lazy PluginLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public String operationType() {
        return ApprovalRequest.OpType.PLUGIN_SUSPEND;
    }

    @Override
    public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
        if (!approved) {
            log.info("plugin_suspend_rejected approvalId={} target={} reason={}", approval.id, approval.targetResourceId, reason);
            return;
        }
        lifecycle.applySuspend(approval.targetResourceId, checker);
    }
}
