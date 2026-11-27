package com.fsp.core.approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {
    List<ApprovalRequest> findByTenantIdOrderByRequestedAtDesc(UUID tenantId);
    List<ApprovalRequest> findByTenantIdAndStatusOrderByRequestedAtDesc(UUID tenantId, String status);
    List<ApprovalRequest> findByTenantIdAndStatusInOrderByRequestedAtDesc(UUID tenantId, java.util.Collection<String> statuses);
    List<ApprovalRequest> findByTenantIdAndOperationTypeOrderByRequestedAtDesc(UUID tenantId, String operationType);
    List<ApprovalRequest> findByTenantIdAndOperationTypeAndStatusInOrderByRequestedAtDesc(
        UUID tenantId, String operationType, java.util.Collection<String> statuses);
    List<ApprovalRequest> findByTargetResourceIdAndStatus(UUID targetResourceId, String status);
    /** Live approvals of one page of targets (bounded by the page size — e.g. the branch list). */
    List<ApprovalRequest> findByTargetResourceIdInAndStatus(java.util.Collection<UUID> targetResourceIds, String status);
    Optional<ApprovalRequest> findByOperationTypeAndTargetResourceId(String operationType, UUID targetResourceId);

    /** A target (e.g. a reused layout draft row) may carry many resolved approvals; ask only about live ones. */
    boolean existsByOperationTypeAndTargetResourceIdAndStatus(String operationType, UUID targetResourceId, String status);
}
