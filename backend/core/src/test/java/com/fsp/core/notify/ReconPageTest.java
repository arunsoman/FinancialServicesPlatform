package com.fsp.core.notify;

import com.fsp.core.common.ApiException;
import com.fsp.core.identity.Person;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.RbacRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fsp.core.identity.PersonRepository;
import com.fsp.core.notify.NotificationChannelPrefRepository;
import com.fsp.core.notify.PushSubscriptionRepository;

/**
 * Stage 6 recon-page dispatch (plan §11; docs/architecture/13 §8): P0 recon violations
 * become notification rows (V29/V31 channel plane does delivery) addressed to every
 * active holder of {@code recon.view} — machine input strictly validated, zero holders
 * pages nobody, severity whitelist closed.
 */
class ReconPageTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final PushSubscriptionRepository subs = mock(PushSubscriptionRepository.class);
    private final NotificationChannelPrefRepository prefs = mock(NotificationChannelPrefRepository.class);
    private final RbacRepository rbac = mock(RbacRepository.class);
    private final PersonRepository persons = mock(PersonRepository.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private NotificationService svc;

    @BeforeEach
    void setUp() {
        svc = new NotificationService(repo, subs, prefs, persons, rbac, outbox);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void pagesEveryReconViewHolderAndQueuesPushOutbox() {
        Person p1 = person(UUID.randomUUID());
        Person p2 = person(UUID.randomUUID());
        when(rbac.activePersonsWithPermission(TENANT, "recon.view")).thenReturn(List.of(p1, p2));

        int created = svc.reconPage(TENANT, "PSP_CLEARING_RESIDUAL", "P0",
            "clearing not zero: position=3000");

        assertEquals(2, created);
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(repo, times(2)).save(cap.capture());
        for (Notification n : cap.getAllValues()) {
            assertEquals(NotificationService.TYPE_RECON_VIOLATION, n.type);
            assertEquals("PSP_CLEARING_RESIDUAL", n.opType);
            assertEquals("P0 recon violation: PSP_CLEARING_RESIDUAL", n.title);
        }
        verify(outbox, times(1)).record(eq("Notification"), any(UUID.class),
            eq("Notification.PushRequired"), any(String.class));
    }

    @Test
    void noHoldersPagesNobodyAndWritesNoOutbox() {
        when(rbac.activePersonsWithPermission(TENANT, "recon.view")).thenReturn(List.of());
        assertEquals(0, svc.reconPage(TENANT, "PSP_PAY_OVER_BREACH", "P0", "x"));
        verify(repo, never()).save(any());
        verify(outbox, never()).record(any(), any(), any(), any());
    }

    @Test
    void machineInputIsWhitelistValidated() {
        assertThrows(ApiException.class,
            () -> svc.reconPage(TENANT, "PSP_X", "P9", "d"));
        assertThrows(ApiException.class, () -> svc.reconPage(TENANT, " ", "P0", "d"));
        assertThrows(ApiException.class, () -> svc.reconPage(TENANT, "x".repeat(65), "P0", "d"));
    }

    @Test
    void detailIsTruncatedToSafeBound() {
        Person p1 = person(UUID.randomUUID());
        when(rbac.activePersonsWithPermission(TENANT, "recon.view")).thenReturn(List.of(p1));
        svc.reconPage(TENANT, "EMI_FLOAT_SHORTFALL", "P0", "d".repeat(5000));
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(repo).save(cap.capture());
        assertEquals(2000, cap.getValue().body.length());
    }

    private Person person(UUID id) {
        Person p = new Person();
        p.id = id;
        p.tenantId = TENANT;
        p.status = "ACTIVE";
        return p;
    }
}
