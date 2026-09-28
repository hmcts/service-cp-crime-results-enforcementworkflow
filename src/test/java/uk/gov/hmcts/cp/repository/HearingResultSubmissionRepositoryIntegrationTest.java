package uk.gov.hmcts.cp.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.integration.IntegrationTestBase;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HearingResultSubmissionRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private HearingResultSubmissionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void flyway_should_have_applied_v1_001() {
        final Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1.001' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
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
    void exists_by_hearing_case_defendant_should_find_saved_row() {
        final UUID hearingId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final UUID defendantId = UUID.randomUUID();
        repository.saveAndFlush(submission(hearingId, caseId, defendantId, null));

        assertThat(repository.existsByHearingIdAndCaseIdAndDefendantId(hearingId, caseId, defendantId)).isTrue();
        assertThat(repository.existsByHearingIdAndCaseIdAndDefendantId(hearingId, caseId, UUID.randomUUID())).isFalse();
    }

    @Test
    void duplicate_hearing_case_defendant_should_violate_unique_index() {
        final UUID hearingId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final UUID defendantId = UUID.randomUUID();
        repository.saveAndFlush(submission(hearingId, caseId, defendantId, null));

        assertThatThrownBy(() -> repository.saveAndFlush(submission(hearingId, caseId, defendantId, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static HearingResultSubmissionEntity submission(final UUID hearingId, final UUID caseId,
                                                            final UUID defendantId, final String requestJson) {
        final HearingResultSubmissionEntity entity = new HearingResultSubmissionEntity();
        entity.setId(UUID.randomUUID());
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
