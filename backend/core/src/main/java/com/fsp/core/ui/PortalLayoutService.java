package com.fsp.core.ui;

import com.fsp.core.approval.ApprovalRequestRepository;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.KeyLocks;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Portal layout flows (plans/widget-harness-prd.md §4): drafts save freely to builders
 * with {@code ui.layout.manage}; PUBLISHING is maker-checker — submit opens a
 * {@code UI_LAYOUT_PUBLISH} approval, resolution publishes (or leaves the draft). All
 * permission checks run against the platform root tenant: layouts are platform-global.
 */
@Slf4j
@Service
public class PortalLayoutService {

    private final UiLayoutRepository layouts;
    private final TenantRepository tenants;
    private final PermissionService permissions;
    private final ApprovalService approvals;
    private final ApprovalRequestRepository approvalRequests;
    private final LayoutValidator validator;
    private final KeyLocks keyLocks;

    public PortalLayoutService(UiLayoutRepository layouts, TenantRepository tenants,
                               PermissionService permissions, ApprovalService approvals,
                               ApprovalRequestRepository approvalRequests, LayoutValidator validator,
                               KeyLocks keyLocks) {
        this.layouts = layouts;
        this.tenants = tenants;
        this.permissions = permissions;
        this.approvals = approvals;
        this.approvalRequests = approvalRequests;
        this.validator = validator;
        this.keyLocks = keyLocks;
    }

    @Transactional
    public UiLayout saveDraft(String portal, UiDtos.LayoutSaveRequest req, Actor actor) {
        requireManage(actor);
        String normalized = validator.validate(req.layoutJson());
        if (req.schemaVersion() < 1) {
            throw ApiException.badRequest("UI_LAYOUT_MALFORMED", "schemaVersion must be >= 1");
        }
        UiLayout row = layouts.findFirstByPortalAndStatus(portal, UiLayout.Status.DRAFT)
            .orElseGet(() -> {
                UiLayout fresh = new UiLayout();
                fresh.id = UUID.randomUUID();
                fresh.portal = portal;
                fresh.status = UiLayout.Status.DRAFT;
                fresh.createdAt = Instant.now();
                return fresh;
            });
        // Draft lock while publishing (maker-checker integrity): once the maker submits for
        // approval, the draft row MUST NOT mutate — the checker decides on the submitted
        // snapshot and the handler publishes that snapshot, never a later silent edit.
        if (approvalRequests.existsByOperationTypeAndTargetResourceIdAndStatus(
            ApprovalRequest.OpType.UI_LAYOUT_PUBLISH, row.id, ApprovalRequest.Status.PENDING_CHECKER)) {
            log.warn("ui_layout_save_locked portal={} layoutId={} actor={}", portal, row.id, actor.username());
            throw ApiException.conflict("APPROVAL_STATE",
                "Draft is locked while its publish approval is pending; approve or reject it first");
        }
        row.layoutJson = normalized;
        row.schemaVersion = req.schemaVersion();
        row.makerId = actor.personId();
        row.updatedAt = Instant.now();
        layouts.save(row);
        log.info("ui_layout_saved portal={} layoutId={} maker={}", portal, row.id, actor.username());
        return row;
    }

    @Transactional
    public UiLayout submit(String portal, Actor actor) {
        requireManage(actor);
        UiLayout draft = layouts.findFirstByPortalAndStatus(portal, UiLayout.Status.DRAFT)
            .orElseThrow(() -> ApiException.notFound("UI_LAYOUT_NO_DRAFT", "No draft layout for " + portal));
        // Serialize per draft id (§2d): the pending-existence check + open must be one step,
        // so two concurrent submits cannot open two UI_LAYOUT_PUBLISH rows for one draft
        // (backed by the partial unique index in V27).
        return keyLocks.withLock("ui-layout", draft.id, () -> {
            boolean pending = approvalRequests.existsByOperationTypeAndTargetResourceIdAndStatus(
                ApprovalRequest.OpType.UI_LAYOUT_PUBLISH, draft.id, ApprovalRequest.Status.PENDING_CHECKER);
            if (pending) {
                log.warn("ui_layout_submit_conflict portal={} layoutId={} actor={}", portal, draft.id, actor.username());
                throw ApiException.conflict("APPROVAL_STATE", "A publish approval is already pending for this draft");
            }
            UUID rootTenantId = rootTenantId();
            var approval = approvals.open(rootTenantId, ApprovalRequest.OpType.UI_LAYOUT_PUBLISH,
                draft.id, actor, "Publish portal layout '" + portal + "'", draft.layoutJson);
            draft.publishedApprovalId = approval.id;
            draft.updatedAt = Instant.now();
            layouts.save(draft);
            log.info("ui_layout_submitted portal={} layoutId={} approvalId={} maker={}",
                portal, draft.id, approval.id, actor.username());
            return draft;
        });
    }

    @Transactional(readOnly = true)
    public UiLayout draft(String portal, Actor actor) {
        requireManage(actor);
        return layouts.findFirstByPortalAndStatus(portal, UiLayout.Status.DRAFT)
            .orElseThrow(() -> ApiException.notFound("UI_LAYOUT_NO_DRAFT", "No draft layout for " + portal));
    }

    @Transactional(readOnly = true)
    public UiDtos.LayoutView published(String portal, Actor actor) {
        if (actor == null) {
            throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        }
        UiLayout row = layouts.findFirstByPortalAndStatus(portal, UiLayout.Status.PUBLISHED)
            .orElseThrow(() -> ApiException.notFound("UI_LAYOUT_NOT_PUBLISHED", "No published layout for " + portal));
        return toView(row);
    }

    @Transactional(readOnly = true)
    public List<UiDtos.LayoutSummary> summaries(String portal, Actor actor) {
        requireManage(actor);
        return layouts.findAllByPortalAndStatus(portal, UiLayout.Status.DRAFT).stream()
            .map(l -> new UiDtos.LayoutSummary(portal, l.status, l.makerId, l.updatedAt.toString(), l.publishedApprovalId))
            .toList();
    }

    private void requireManage(Actor actor) {
        if (actor == null) {
            throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        }
        permissions.require(actor, "ui.layout.manage", rootTenantId());
    }

    private UUID rootTenantId() {
        return tenants.findByParentTenantId(null).getFirst().id;
    }

    private static UiDtos.LayoutView toView(UiLayout row) {
        return new UiDtos.LayoutView(row.id, row.portal, row.status, row.layoutJson,
            row.schemaVersion, row.makerId, row.updatedAt.toString(), row.versionNum);
    }
}
