package com.fsp.core.plugin.registry;

import com.fsp.core.approval.ApprovalHandler;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.common.Actor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** PLUGIN_RESUME approved → the suspended version is back in service. */
@Slf4j
@Component
public class PluginResumeApprovalHandler implements ApprovalHandler {

    private final PluginLifecycleService lifecycle;

    public PluginResumeApprovalHandler(@org.springframework.context.annotation.Lazy PluginLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public String operationType() {
        return ApprovalRequest.OpType.PLUGIN_RESUME;
    }

    @Override
    public void apply(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
        if (!approved) {
            log.info("plugin_resume_rejected approvalId={} target={} reason={}", approval.id, approval.targetResourceId, reason);
            return;
        }
        lifecycle.applyResume(approval.targetResourceId, checker);
    }
}
