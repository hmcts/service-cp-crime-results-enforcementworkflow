package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.config.NotificationProperties;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementNotificationNowEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.openapi.model.EnforcementNotification;
import uk.gov.hmcts.cp.openapi.model.EnforcementNow;
import uk.gov.hmcts.cp.repository.EnforcementNotificationNowRepository;
import uk.gov.hmcts.cp.repository.EnforcementNotificationRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Feature 003 (FR-003, FR-004, FR-011, research R1, R2): accepting a notification. */
class NotificationServiceTest {

    private static final UUID CALLER = UUID.fromString("5f1a6c2e-0000-4000-8000-00000000fa01");
    private static final UUID HEARING_ID = UUID.randomUUID();
    private static final UUID MASTER_DEFENDANT_ID = UUID.randomUUID();

    private final NotificationProperties properties = new NotificationProperties();
    private final EnforcementNotificationRepository notifications = mock(EnforcementNotificationRepository.class);
    private final EnforcementNotificationNowRepository nows = mock(EnforcementNotificationNowRepository.class);
    private final NotificationTasks tasks = mock(NotificationTasks.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final NotificationService service;

    NotificationServiceTest() {
        properties.setAllowedSystemUsers(new ArrayList<>(List.of(CALLER.toString())));
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new NotificationService(properties, notifications, nows, tasks, new TransactionTemplate(transactionManager));
    }

    @Test
    void new_notification_should_be_stored_with_its_request_ids_and_queued() {
        final UUID r1 = UUID.randomUUID();
        final UUID r2 = UUID.randomUUID();

        final UUID id = service.notify(CALLER, notification(r1, r2));

        verify(notifications).saveAndFlush(org.mockito.ArgumentMatchers.argThat((EnforcementNotificationEntity n) ->
                n.getId().equals(id) && n.getStatus() == NotificationStatus.RECEIVED && n.getHearingId().equals(HEARING_ID)
                        && n.getCaller().equals(CALLER.toString())));
        verify(nows).saveAndFlush(new EnforcementNotificationNowEntityMatcher(r1, id).matcher());
        verify(nows).saveAndFlush(new EnforcementNotificationNowEntityMatcher(r2, id).matcher());
        verify(tasks).queueProcess(id);
    }

    @Test
    void repeated_notification_should_return_the_earlier_id_and_do_nothing() {
        final UUID r1 = UUID.randomUUID();
        final UUID earlier = UUID.randomUUID();
        when(nows.findByRequestIdIn(List.of(r1))).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, earlier, MASTER_DEFENDANT_ID)));
        when(nows.findByNotificationId(earlier)).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, earlier, MASTER_DEFENDANT_ID)));
        when(notifications.findById(earlier)).thenReturn(Optional.of(stored(earlier, HEARING_ID)));

        assertThat(service.notify(CALLER, notification(r1))).isEqualTo(earlier);

        verify(notifications, never()).saveAndFlush(any());
        verify(tasks, never()).queueProcess(any());
    }

    @Test
    void reshare_flag_should_be_stored() {
        service.notify(CALLER, notification(UUID.randomUUID()).isReshare(true));

        verify(notifications).saveAndFlush(org.mockito.ArgumentMatchers.argThat((EnforcementNotificationEntity n) -> n.isReshare()));
    }

    @Test
    void known_request_ids_with_another_reshare_flag_should_be_a_conflict() {
        final UUID r1 = UUID.randomUUID();
        final UUID earlier = UUID.randomUUID();
        when(nows.findByRequestIdIn(List.of(r1))).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, earlier, MASTER_DEFENDANT_ID)));
        when(notifications.findById(earlier)).thenReturn(Optional.of(stored(earlier, HEARING_ID)));

        assertThatThrownBy(() -> service.notify(CALLER, notification(r1).isReshare(true)))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.CONFLICT));
    }

    // FR-011: a repeat is the same hearing, hearing date, reshare flag and request ids (with their master defendants)
    @Test
    void known_request_ids_for_another_hearing_should_be_a_conflict() {
        final UUID r1 = UUID.randomUUID();
        final UUID earlier = UUID.randomUUID();
        when(nows.findByRequestIdIn(List.of(r1))).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, earlier, MASTER_DEFENDANT_ID)));
        when(notifications.findById(earlier)).thenReturn(Optional.of(stored(earlier, UUID.randomUUID())));

        assertThatThrownBy(() -> service.notify(CALLER, notification(r1)))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.CONFLICT));
        verify(tasks, never()).queueProcess(any());
    }

    @Test
    void partly_known_request_ids_should_be_a_conflict() {
        final UUID r1 = UUID.randomUUID();
        final UUID r2 = UUID.randomUUID();
        when(nows.findByRequestIdIn(List.of(r1, r2))).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, UUID.randomUUID(),
                MASTER_DEFENDANT_ID)));

        assertThatThrownBy(() -> service.notify(CALLER, notification(r1, r2)))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.CONFLICT));
        verify(tasks, never()).queueProcess(any());
    }

    @Test
    void known_request_ids_for_another_hearing_date_should_be_a_conflict() {
        final UUID r1 = UUID.randomUUID();
        final UUID earlier = UUID.randomUUID();
        when(nows.findByRequestIdIn(List.of(r1))).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, earlier, MASTER_DEFENDANT_ID)));
        final EnforcementNotificationEntity otherDay = stored(earlier, HEARING_ID);
        otherDay.setHearingDate(LocalDate.parse("2026-10-18"));
        when(notifications.findById(earlier)).thenReturn(Optional.of(otherDay));

        assertThatThrownBy(() -> service.notify(CALLER, notification(r1)))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.CONFLICT));
    }

    @Test
    void request_ids_of_two_earlier_notifications_should_be_a_conflict() {
        final UUID r1 = UUID.randomUUID();
        final UUID r2 = UUID.randomUUID();
        when(nows.findByRequestIdIn(List.of(r1, r2))).thenReturn(List.of(
                new EnforcementNotificationNowEntity(r1, UUID.randomUUID(), MASTER_DEFENDANT_ID),
                new EnforcementNotificationNowEntity(r2, UUID.randomUUID(), MASTER_DEFENDANT_ID)));

        assertThatThrownBy(() -> service.notify(CALLER, notification(r1, r2)))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.CONFLICT));
        verify(notifications, never()).saveAndFlush(any());
    }

    @Test
    void missing_caller_should_be_rejected() {
        assertThatThrownBy(() -> service.notify(null, notification(UUID.randomUUID())))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.CALLER_NOT_ALLOWED));
    }

    // the ids are inserted in order, so concurrent notifications sharing ids lock them in the same order
    @Test
    void request_ids_should_be_stored_in_id_order() {
        final UUID high = UUID.fromString("20000000-0000-4000-8000-000000000001");
        final UUID low = UUID.fromString("10000000-0000-4000-8000-000000000001");

        service.notify(CALLER, notification(high, low));

        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(nows);
        order.verify(nows).saveAndFlush(org.mockito.ArgumentMatchers.argThat((EnforcementNotificationNowEntity n) -> n.getRequestId().equals(low)));
        order.verify(nows).saveAndFlush(org.mockito.ArgumentMatchers.argThat((EnforcementNotificationNowEntity n) -> n.getRequestId().equals(high)));
    }

    // FR-011: only an exact repeat is a no-op; fewer request ids than the earlier notification is inconsistent
    @Test
    void subset_of_an_earlier_notification_s_request_ids_should_be_a_conflict() {
        final UUID r1 = UUID.randomUUID();
        final UUID r2 = UUID.randomUUID();
        final UUID earlier = UUID.randomUUID();
        when(nows.findByRequestIdIn(List.of(r1))).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, earlier, MASTER_DEFENDANT_ID)));
        when(nows.findByNotificationId(earlier)).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, earlier, MASTER_DEFENDANT_ID),
                new EnforcementNotificationNowEntity(r2, earlier, MASTER_DEFENDANT_ID)));
        when(notifications.findById(earlier)).thenReturn(Optional.of(stored(earlier, HEARING_ID)));

        assertThatThrownBy(() -> service.notify(CALLER, notification(r1)))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.CONFLICT));
    }

    @Test
    void known_request_id_with_another_master_defendant_should_be_a_conflict() {
        final UUID r1 = UUID.randomUUID();
        final UUID earlier = UUID.randomUUID();
        when(nows.findByRequestIdIn(List.of(r1))).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, earlier, UUID.randomUUID())));
        when(nows.findByNotificationId(earlier)).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, earlier, UUID.randomUUID())));
        when(notifications.findById(earlier)).thenReturn(Optional.of(stored(earlier, HEARING_ID)));

        assertThatThrownBy(() -> service.notify(CALLER, notification(r1)))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.CONFLICT));
    }

    @Test
    void concurrent_identical_notification_should_return_the_id_stored_first() {
        final UUID r1 = UUID.randomUUID();
        final UUID first = UUID.randomUUID();
        final java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        when(nows.findByRequestIdIn(List.of(r1))).thenAnswer(invocation -> lookups.getAndIncrement() == 0 ? List.of()
                : List.of(new EnforcementNotificationNowEntity(r1, first, MASTER_DEFENDANT_ID)));
        when(nows.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        when(notifications.findById(first)).thenReturn(Optional.of(stored(first, HEARING_ID)));
        when(nows.findByNotificationId(first)).thenReturn(List.of(new EnforcementNotificationNowEntity(r1, first, MASTER_DEFENDANT_ID)));

        assertThat(service.notify(CALLER, notification(r1))).isEqualTo(first);
    }

    @Test
    void integrity_violation_without_a_stored_notification_should_not_be_hidden() {
        final UUID r1 = UUID.randomUUID();
        when(nows.findByRequestIdIn(List.of(r1))).thenReturn(List.of());
        when(nows.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("not null"));

        assertThatThrownBy(() -> service.notify(CALLER, notification(r1)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void caller_not_allowed_should_be_rejected() {
        assertThatThrownBy(() -> service.notify(UUID.randomUUID(), notification(UUID.randomUUID())))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.CALLER_NOT_ALLOWED));
        verify(notifications, never()).saveAndFlush(any());
    }

    @Test
    void empty_allow_list_should_close_the_endpoint() {
        properties.setAllowedSystemUsers(new ArrayList<>());

        assertThatThrownBy(() -> service.notify(CALLER, notification(UUID.randomUUID())))
                .isInstanceOf(NotificationRejectedException.class);
    }

    @Test
    void repeated_request_id_in_one_body_should_be_invalid() {
        final UUID r1 = UUID.randomUUID();

        assertThatThrownBy(() -> service.notify(CALLER, notification(r1, r1)))
                .isInstanceOfSatisfying(NotificationRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(NotificationRejectedException.Reason.INVALID));
    }

    private static EnforcementNotification notification(final UUID... requestIds) {
        final List<EnforcementNow> items = new ArrayList<>();
        for (final UUID requestId : requestIds) {
            items.add(new EnforcementNow().requestId(requestId).masterDefendantId(MASTER_DEFENDANT_ID));
        }
        return new EnforcementNotification().hearingId(HEARING_ID).hearingDate(LocalDate.parse("2026-10-19")).isReshare(false).nows(items);
    }

    private static EnforcementNotificationEntity stored(final UUID id, final UUID hearingId) {
        final EnforcementNotificationEntity entity = new EnforcementNotificationEntity();
        entity.setId(id);
        entity.setHearingId(hearingId);
        entity.setHearingDate(LocalDate.parse("2026-10-19"));
        return entity;
    }

    private record EnforcementNotificationNowEntityMatcher(UUID requestId, UUID notificationId) {
        EnforcementNotificationNowEntity matcher() {
            return org.mockito.ArgumentMatchers.argThat(now -> now.getRequestId().equals(requestId)
                    && now.getNotificationId().equals(notificationId) && now.getMasterDefendantId().equals(MASTER_DEFENDANT_ID));
        }
    }
}
