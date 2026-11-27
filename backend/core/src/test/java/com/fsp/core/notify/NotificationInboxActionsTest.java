package com.fsp.core.notify;

import com.fsp.core.common.ApiException;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.RbacRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V38 actionable inbox: a pointer stays pending until it is acted upon. Approval pointers clear
 * only by deciding the approval (never by dismissing); a person can park any pending pointer
 * for later; every write is scoped to the owner, so another person's id is a plain 404.
 */
class NotificationInboxActionsTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();

    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final NotificationService svc = new NotificationService(repo, mock(PushSubscriptionRepository.class),
        mock(NotificationChannelPrefRepository.class), mock(PersonRepository.class), mock(RbacRepository.class),
        mock(OutboxService.class));

    private Notification mine(String type) {
        Notification n = new Notification();
        n.id = UUID.randomUUID();
        n.personId = ME;
        n.tenantId = UUID.randomUUID();
        n.type = type;
        n.opType = "USER_UPDATE";
        n.title = "Awaiting approval";
        n.body = "A user change is waiting for a checker decision.";
        n.resourceType = "ApprovalRequest";
        n.resourceId = UUID.randomUUID();
        n.link = "/approvals";
        n.createdAt = Instant.now();
        when(repo.findByIdAndPersonId(n.id, ME)).thenReturn(Optional.of(n));
        return n;
    }

    @Test
    void snoozeParksAPendingNotificationUntilTheGivenInstant() {
        Notification n = mine(NotificationService.TYPE_APPROVAL_OPEN);
        Instant until = Instant.now().plus(Duration.ofHours(4));
        when(repo.snooze(n.id, ME, until)).thenReturn(1);

        NotificationService.NotificationView v = svc.snooze(ME, n.id, until);

        verify(repo).snooze(n.id, ME, until);
        assertThat(v.snoozedUntil()).isEqualTo(until.toString());
        assertThat(v.actionable()).isTrue();
        assertThat(v.resourceId()).isEqualTo(n.resourceId);
    }

    @Test
    void snoozeOutsideOneMinuteToThirtyDaysIsRefused() {
        Notification n = mine(NotificationService.TYPE_APPROVAL_OPEN);
        assertThatThrownBy(() -> svc.snooze(ME, n.id, Instant.now().plusSeconds(10)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SNOOZE_RANGE"));
        assertThatThrownBy(() -> svc.snooze(ME, n.id, Instant.now().plus(Duration.ofDays(31))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SNOOZE_RANGE"));
        assertThatThrownBy(() -> svc.snooze(ME, n.id, null))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SNOOZE_UNTIL_REQUIRED"));
        verify(repo, never()).snooze(any(), any(), any());
    }

    /** The approval was decided between the person opening the bell and pressing snooze. */
    @Test
    void snoozeLosesToAConcurrentDecision() {
        Notification n = mine(NotificationService.TYPE_APPROVAL_OPEN);
        Instant until = Instant.now().plus(Duration.ofHours(1));
        when(repo.snooze(n.id, ME, until)).thenReturn(0); // resolved_at IS NULL precondition failed

        assertThatThrownBy(() -> svc.snooze(ME, n.id, until))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NOTIFICATION_RESOLVED"));
    }

    @Test
    void anotherPersonsNotificationIsNotFound() {
        Notification n = mine(NotificationService.TYPE_APPROVAL_OPEN);
        when(repo.findByIdAndPersonId(n.id, SOMEONE_ELSE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.snooze(SOMEONE_ELSE, n.id, Instant.now().plus(Duration.ofHours(1))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NOTIFICATION_NOT_FOUND"));
        assertThatThrownBy(() -> svc.dismiss(SOMEONE_ELSE, n.id))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NOTIFICATION_NOT_FOUND"));
        assertThatThrownBy(() -> svc.wake(SOMEONE_ELSE, n.id))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NOTIFICATION_NOT_FOUND"));
        verify(repo, never()).snooze(any(), any(), any());
        verify(repo, never()).dismiss(any(), any(), any());
    }

    /** Dismissing an approval pointer would hide four-eyes work: decide it or snooze it. */
    @Test
    void approvalPointerCannotBeDismissed() {
        Notification n = mine(NotificationService.TYPE_APPROVAL_OPEN);
        assertThatThrownBy(() -> svc.dismiss(ME, n.id))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NOTIFICATION_ACTIONABLE"));
        verify(repo, never()).dismiss(any(), any(), any());
    }

    @Test
    void informationalNoticeCanBeDismissed() {
        Notification n = mine(NotificationService.TYPE_RECON_VIOLATION);
        svc.dismiss(ME, n.id);
        verify(repo).dismiss(eq(n.id), eq(ME), any(Instant.class));
    }

    @Test
    void decidingAnApprovalResolvesEveryCheckersPointer() {
        UUID approval = UUID.randomUUID();
        when(repo.resolveForResource(eq("ApprovalRequest"), eq(approval), any(Instant.class))).thenReturn(3);
        assertThat(svc.resolveApproval(approval)).isEqualTo(3);
    }

    @Test
    void inboxStateIsAClosedSet() {
        assertThat(NotificationService.parseState(null)).isEqualTo(NotificationService.InboxState.ALL);
        assertThat(NotificationService.parseState("pending")).isEqualTo(NotificationService.InboxState.PENDING);
        assertThat(NotificationService.parseState("SNOOZED")).isEqualTo(NotificationService.InboxState.SNOOZED);
        assertThatThrownBy(() -> NotificationService.parseState("everything"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("STATE_INVALID"));
    }
}
