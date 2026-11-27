package com.fsp.core.audit;

import com.fsp.core.common.Actor;
import com.fsp.core.common.HashUtil;
import com.fsp.core.common.RequestCorrelation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative append-only evidence writer. Called at core-controlled mutation
 * boundaries only (AGENTS.md §3.6). Synchronous write; the caller's transaction
 * makes audit + business state atomic.
 *
 * While a person acts as another tenant (plans/act-as-tenant-plan.md), {@code actorId}
 * is always the real signed-in user and {@code actingTenantId} / {@code actSessionId}
 * name the tenant acted on behalf of — the real person is never hidden.
 */
@Service
public class AuditService {

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    public String hashState(Object snapshot) {
        return HashUtil.sha256Hex(String.valueOf(snapshot));
    }

    public void record(UUID actorId, UUID checkerId, UUID tenantId, UUID bookId,
                       String actionType, String resourceType, String resourceId,
                       String beforeStateHash, String afterStateHash, boolean simulated) {
        String correlation = RequestCorrelation.currentCorrelation();
        if (correlation == null) correlation = UUID.randomUUID().toString();

        Actor actor = RequestCorrelation.currentActor();
        if (actorId == null && actor != null) {
            actorId = actor.personId();
        }

        AuditRecord r = new AuditRecord();
        r.actorId = actorId;
        r.checkerId = checkerId;
        r.tenantId = tenantId;
        r.bookId = bookId;
        r.actionType = actionType;
        r.resourceType = resourceType;
        r.resourceId = resourceId;
        r.beforeStateHash = beforeStateHash;
        r.afterStateHash = afterStateHash;
        r.correlationId = correlation;
        r.ipAddress = RequestCorrelation.clientIp();
        r.clientId = RequestCorrelation.clientId();
        UUID home = RequestCorrelation.homeTenantId();
        r.actingTenantId = home == null ? (actor != null ? actor.activeTenantId() : null) : home;
        // plan C6: a write made while acting carries the verified session it ran under
        r.actSessionId = (home != null && actor != null
                && (actor.type() == Actor.ActorType.ACTING || actor.type() == Actor.ActorType.DELEGATED))
            ? RequestCorrelation.actSessionId()
            : null;
        r.simulated = simulated;
        repository.save(r);
    }

    /** Record with an explicit act session id (used by ActAsService start/end audit). */
    public void record(UUID actorId, UUID checkerId, UUID tenantId, UUID bookId,
                       String actionType, String resourceType, String resourceId,
                       String beforeStateHash, String afterStateHash, boolean simulated,
                       UUID actingTenantId, String actSessionId) {
        String correlation = RequestCorrelation.currentCorrelation();
        if (correlation == null) correlation = UUID.randomUUID().toString();

        AuditRecord r = new AuditRecord();
        r.actorId = actorId;
        r.checkerId = checkerId;
        r.tenantId = tenantId;
        r.bookId = bookId;
        r.actionType = actionType;
        r.resourceType = resourceType;
        r.resourceId = resourceId;
        r.beforeStateHash = beforeStateHash;
        r.afterStateHash = afterStateHash;
        r.correlationId = correlation;
        r.ipAddress = RequestCorrelation.clientIp();
        r.clientId = RequestCorrelation.clientId();
        r.actingTenantId = actingTenantId;
        r.actSessionId = actSessionId;
        r.simulated = simulated;
        repository.save(r);
    }
}
