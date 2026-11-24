package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.hmcts.cp.entity.Area;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.entity.ResultNotificationStatus;
import uk.gov.hmcts.cp.repository.EnforcementResultNotificationRepository;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 005 (research.md R3): the Previous Share Snapshot query - the single most recent {@code SENT} row for the
 * same hearing+reference, never an earlier or a different reference's row.
 */
class EnforcementResultNotificationRepositoryIntegrationTest extends IntegrationTestBase {

    private static final UUID HEARING_ID = UUID.randomUUID();
    private static final String REFERENCE = "9990001112A";

    @Autowired
    private EnforcementResultNotificationRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void should_return_empty_when_no_sent_row_exists() {
        assertThat(repository.findPreviousShareSnapshot(HEARING_ID, REFERENCE)).isEmpty();
    }

    @Test
    void should_ignore_a_non_sent_row() {
        save(ResultNotificationStatus.SUPPRESSED, "[\"suppressed\"]");

        assertThat(repository.findPreviousShareSnapshot(HEARING_ID, REFERENCE)).isEmpty();
    }

    @Test
    void should_return_the_single_sent_row() {
        final EnforcementResultNotificationEntity sent = save(ResultNotificationStatus.SENT, "[\"first\"]");

        assertThat(repository.findPreviousShareSnapshot(HEARING_ID, REFERENCE).orElseThrow().getId()).isEqualTo(sent.getId());
    }

    @Test
    void should_return_the_most_recent_sent_row_not_an_earlier_one() {
        save(ResultNotificationStatus.SENT, "[\"first\"]");
        sleepOneMillisecond();
        final EnforcementResultNotificationEntity second = save(ResultNotificationStatus.SENT, "[\"second\"]");
        sleepOneMillisecond();
        final EnforcementResultNotificationEntity third = save(ResultNotificationStatus.SENT, "[\"third\"]");

        assertThat(repository.findPreviousShareSnapshot(HEARING_ID, REFERENCE).orElseThrow().getId()).isEqualTo(third.getId());
        assertThat(second.getId()).isNotEqualTo(third.getId());
    }

    @Test
    void should_never_return_a_row_for_a_different_hearing_or_reference() {
        save(ResultNotificationStatus.SENT, "[\"this-hearing\"]");
        final EnforcementResultNotificationEntity entity = new EnforcementResultNotificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setTriggerNotificationId(UUID.randomUUID());
        entity.setHearingId(UUID.randomUUID()); // a different hearing
        entity.setHearingDate(LocalDate.parse("2026-05-03"));
        entity.setRelatedReferenceNumber(REFERENCE);
        entity.setNotificationType(NotificationType.FIRST_SHARE);
        entity.setDivisionCode("123");
        entity.setArea(Area.ENFORCEMENT);
        entity.setCaseUrn("URN-OTHER");
        entity.setStatus(ResultNotificationStatus.SENT);
        entity.setResultShortCodes("[\"other-hearing\"]");
        repository.saveAndFlush(entity);

        assertThat(repository.findPreviousShareSnapshot(HEARING_ID, REFERENCE).orElseThrow().getResultShortCodes())
                .isEqualTo("[\"this-hearing\"]");
    }

    private static void sleepOneMillisecond() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private EnforcementResultNotificationEntity save(final ResultNotificationStatus status, final String resultShortCodes) {
        final EnforcementResultNotificationEntity entity = new EnforcementResultNotificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setTriggerNotificationId(UUID.randomUUID());
        entity.setHearingId(HEARING_ID);
        entity.setHearingDate(LocalDate.parse("2026-05-03"));
        entity.setRelatedReferenceNumber(REFERENCE);
        entity.setNotificationType(NotificationType.FIRST_SHARE);
        entity.setDivisionCode("123");
        entity.setArea(Area.ENFORCEMENT);
        entity.setCaseUrn("URN123");
        entity.setStatus(status);
        entity.setResultShortCodes(resultShortCodes);
        return repository.saveAndFlush(entity);
    }
}
