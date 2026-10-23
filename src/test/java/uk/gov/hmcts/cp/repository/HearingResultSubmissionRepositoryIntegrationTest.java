package uk.gov.hmcts.cp.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.cp.entity.GobSentOffenceEntity;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.NowsHandoverStatus;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.integration.IntegrationTestBase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HearingResultSubmissionRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private HearingResultSubmissionRepository repository;

    @Autowired
    private GobSentOffenceRepository sentOffenceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        sentOffenceRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    void flyway_should_have_applied_v1_001_to_v1_003() {
        final Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version in ('1.001', '1.002', '1.003') and success", Integer.class);
        assertThat(applied).isEqualTo(3);
    }

    @Test
    void hand_over_columns_should_default_to_null_and_round_trip() {
        final HearingResultSubmissionEntity saved = repository.saveAndFlush(
                submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null));
        assertThat(repository.findById(saved.getId()).orElseThrow().getNowsHandoverStatus()).isNull();

        saved.setNowsHandoverStatus(NowsHandoverStatus.FAILED);
        saved.setNowsHandoverDetail("HTTP 403");
        saved.setNowsHandoverAt(Instant.parse("2026-05-03T14:31:00Z"));
        repository.saveAndFlush(saved);

        final HearingResultSubmissionEntity loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getNowsHandoverStatus()).isEqualTo(NowsHandoverStatus.FAILED);
        assertThat(loaded.getNowsHandoverDetail()).isEqualTo("HTTP 403");
        assertThat(loaded.getNowsHandoverAt()).isEqualTo(Instant.parse("2026-05-03T14:31:00Z"));
    }

    @Test
    void jsonb_payloads_should_round_trip() {
        final HearingResultSubmissionEntity saved = repository.saveAndFlush(
                submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "{\"caseUrn\": \"E012345678\"}"));

        final HearingResultSubmissionEntity loaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getRequestPayload()).contains("\"caseUrn\"").contains("E012345678");
        assertThat(loaded.getStatus()).isEqualTo(SubmissionStatus.SENDING);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void find_by_correlation_case_defendant_should_find_saved_row() {
        final HearingResultSubmissionEntity saved = repository.saveAndFlush(
                submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null));

        assertThat(repository.findByCorrelationIdAndCaseIdAndDefendantId(saved.getCorrelationId(), saved.getCaseId(),
                saved.getDefendantId())).isPresent();
        assertThat(repository.findByCorrelationIdAndCaseIdAndDefendantId(saved.getCorrelationId(), saved.getCaseId(),
                UUID.randomUUID())).isEmpty();
    }

    // feature 002 R19: a later hearing day of the same hearing is a new share, so a new submission
    @Test
    void same_hearing_case_defendant_with_another_correlation_id_should_be_allowed() {
        final UUID hearingId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final UUID defendantId = UUID.randomUUID();
        repository.saveAndFlush(submission(hearingId, caseId, defendantId, null));

        repository.saveAndFlush(submission(hearingId, caseId, defendantId, null));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void duplicate_correlation_case_defendant_should_violate_unique_index() {
        final HearingResultSubmissionEntity first = repository.saveAndFlush(
                submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null));
        final HearingResultSubmissionEntity second = submission(first.getHearingId(), first.getCaseId(), first.getDefendantId(), null);
        second.setCorrelationId(first.getCorrelationId());

        assertThatThrownBy(() -> repository.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void the_same_offence_should_be_recorded_as_sent_only_once_per_case() {
        final HearingResultSubmissionEntity submission = repository.saveAndFlush(
                submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null));
        final UUID offenceId = UUID.randomUUID();
        sentOffenceRepository.saveAndFlush(sentItem(submission, offenceId, null));

        assertThatThrownBy(() -> sentOffenceRepository.saveAndFlush(sentItem(submission, offenceId, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sentOffenceRepository.findByCaseIdAndOffenceIdIn(submission.getCaseId(), List.of(offenceId))).hasSize(1);
    }

    @Test
    void the_same_non_offence_result_should_be_recorded_as_sent_only_once_per_case() {
        final HearingResultSubmissionEntity submission = repository.saveAndFlush(
                submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null));
        final UUID judicialResultId = UUID.randomUUID();
        sentOffenceRepository.saveAndFlush(sentItem(submission, null, judicialResultId));

        assertThatThrownBy(() -> sentOffenceRepository.saveAndFlush(sentItem(submission, null, judicialResultId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void a_sent_item_should_name_exactly_one_of_offence_and_judicial_result() {
        final HearingResultSubmissionEntity submission = repository.saveAndFlush(
                submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null));

        assertThatThrownBy(() -> sentOffenceRepository.saveAndFlush(sentItem(submission, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sentOffenceRepository.saveAndFlush(sentItem(submission, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static GobSentOffenceEntity sentItem(final HearingResultSubmissionEntity submission, final UUID offenceId,
                                                 final UUID judicialResultId) {
        final GobSentOffenceEntity entity = new GobSentOffenceEntity();
        entity.setId(UUID.randomUUID());
        entity.setCaseId(submission.getCaseId());
        entity.setDefendantId(submission.getDefendantId());
        entity.setOffenceId(offenceId);
        entity.setJudicialResultId(judicialResultId);
        entity.setSubmissionId(submission.getId());
        entity.setCorrelationId(submission.getCorrelationId());
        return entity;
    }

    private static HearingResultSubmissionEntity submission(final UUID hearingId, final UUID caseId,
                                                            final UUID defendantId, final String requestJson) {
        final HearingResultSubmissionEntity entity = new HearingResultSubmissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setCorrelationId(UUID.randomUUID());
        entity.setHearingId(hearingId);
        entity.setCaseId(caseId);
        entity.setDefendantId(defendantId);
        entity.setCaseUrn("E012345678");
        entity.setSharedTime(Instant.parse("2026-05-03T14:30:00Z"));
        entity.setStatus(SubmissionStatus.SENDING);
        entity.setRequestPayload(requestJson);
        return entity;
    }
}
