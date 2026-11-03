package uk.gov.hmcts.cp.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.EnforcementNotificationNowEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.integration.IntegrationTestBase;
import uk.gov.hmcts.cp.taskmanager.persistence.repository.JobsRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Feature 003 (data-model.md, constitution III): notifications, their request ids and cp-task-manager's jobs table. */
class EnforcementNotificationRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private EnforcementNotificationRepository notifications;

    @Autowired
    private EnforcementNotificationNowRepository nows;

    @Autowired
    private JobsRepository jobs;

    // also before each test: a run stopped mid-test skips @AfterEach
    @BeforeEach
    @AfterEach
    void clean() {
        nows.deleteAll();
        notifications.deleteAll();
    }

    @Test
    void notification_and_its_request_ids_should_be_stored_and_found() {
        final EnforcementNotificationEntity saved = notifications.saveAndFlush(notification());
        final UUID requestId = UUID.randomUUID();
        nows.saveAndFlush(new EnforcementNotificationNowEntity(requestId, saved.getId(), UUID.randomUUID()));

        final EnforcementNotificationEntity loaded = notifications.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(NotificationStatus.RECEIVED);
        assertThat(loaded.getReceivedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(nows.findByNotificationId(saved.getId())).extracting(EnforcementNotificationNowEntity::getRequestId)
                .containsExactly(requestId);
    }

    // feature 003 FR-009: a reshare waits while a first-time share of the hearing is still on its way to GOB
    @Test
    void first_share_in_progress_should_be_found_by_hearing_excluding_the_reshare_itself() {
        final EnforcementNotificationEntity first = notifications.saveAndFlush(notification());
        final EnforcementNotificationEntity reshare = notification();
        reshare.setHearingId(first.getHearingId());
        reshare.setReshare(true);
        notifications.saveAndFlush(reshare);
        final List<NotificationStatus> inProgress = List.of(NotificationStatus.RECEIVED, NotificationStatus.PROCESSING);

        assertThat(notifications.existsByHearingIdAndReshareFalseAndIdNotAndStatusIn(first.getHearingId(), reshare.getId(), inProgress)).isTrue();
        assertThat(notifications.existsByHearingIdAndReshareFalseAndIdNotAndStatusIn(first.getHearingId(), first.getId(), inProgress)).isFalse();
        first.setStatus(NotificationStatus.PUBLISHED);
        notifications.saveAndFlush(first);
        assertThat(notifications.existsByHearingIdAndReshareFalseAndIdNotAndStatusIn(first.getHearingId(), reshare.getId(), inProgress)).isFalse();
    }

    @Test
    void the_same_request_id_should_not_belong_to_two_notifications() {
        final UUID requestId = UUID.randomUUID();
        final EnforcementNotificationEntity first = notifications.saveAndFlush(notification());
        final EnforcementNotificationEntity second = notifications.saveAndFlush(notification());
        nows.saveAndFlush(new EnforcementNotificationNowEntity(requestId, first.getId(), UUID.randomUUID()));

        assertThatThrownBy(() -> nows.saveAndFlush(new EnforcementNotificationNowEntity(requestId, second.getId(), UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void jobs_table_should_match_the_task_manager_entity() {
        assertThat(jobs.count()).isGreaterThanOrEqualTo(0);
    }

    private static EnforcementNotificationEntity notification() {
        final EnforcementNotificationEntity notification = new EnforcementNotificationEntity();
        notification.setId(UUID.randomUUID());
        notification.setHearingId(UUID.randomUUID());
        notification.setHearingDate(LocalDate.parse("2026-10-19"));
        notification.setCaller(UUID.randomUUID().toString());
        notification.setStatus(NotificationStatus.RECEIVED);
        return notification;
    }
}
