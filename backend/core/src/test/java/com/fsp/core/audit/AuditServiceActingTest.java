package com.fsp.core.audit;

import com.fsp.core.common.Actor;
import com.fsp.core.common.RequestCorrelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Plan C6 (plans/act-as-tenant-plan.md): a write made while acting is audited as the REAL person
 * and names the act-as session it ran under. Pre-fix the session lookup was a stub returning null,
 * so an act-as write was indistinguishable from the person's own cross-tenant write.
 */
class AuditServiceActingTest {

    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID HOME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final AuditRepository repository = mock(AuditRepository.class);
    private final AuditService audit = new AuditService(repository);

    @AfterEach
    void clear() {
        RequestCorrelation.clear();
    }

    private AuditRecord saved() {
        ArgumentCaptor<AuditRecord> row = ArgumentCaptor.forClass(AuditRecord.class);
        verify(repository).save(row.capture());
        return row.getValue();
    }

    @Test
    void aWriteMadeWhileActingNamesTheRealPersonAndTheSession() {
        RequestCorrelation.setActor(new Actor(PERSON, "platform-admin", TARGET, Actor.ActorType.ACTING, null));
        RequestCorrelation.setHomeTenantId(HOME);
        RequestCorrelation.setActSessionId("sess-1");

        audit.record(null, null, TARGET, null, "USER_CREATE_REQUESTED", "PERSON", "p1", null, "h", false);

        AuditRecord r = saved();
        assertThat(r.actorId).isEqualTo(PERSON);
        assertThat(r.tenantId).isEqualTo(TARGET);
        assertThat(r.actingTenantId).isEqualTo(HOME);
        assertThat(r.actSessionId).isEqualTo("sess-1");
    }

    @Test
    void aPersonsOwnWriteCarriesNoActSession() {
        RequestCorrelation.setActor(new Actor(PERSON, "platform-admin", HOME));
        RequestCorrelation.setActSessionId("stray");   // not acting: must not be attributed

        audit.record(null, null, TARGET, null, "USER_CREATE_REQUESTED", "PERSON", "p1", null, "h", false);

        AuditRecord r = saved();
        assertThat(r.actorId).isEqualTo(PERSON);
        assertThat(r.actSessionId).isNull();
    }
}
