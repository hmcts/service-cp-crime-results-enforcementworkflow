package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import uk.gov.hmcts.cp.entity.HearingResultSubmissionEntity;
import uk.gov.hmcts.cp.entity.SubmissionStatus;
import uk.gov.hmcts.cp.integration.IntegrationTestBase;
import uk.gov.hmcts.cp.mapper.MappingFailureReason;
import uk.gov.hmcts.cp.repository.HearingResultSubmissionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class SubmissionStoreIntegrationTest extends IntegrationTestBase {

    private static final Instant SHARED = Instant.parse("2026-05-03T14:30:00Z");

    @Autowired
    private SubmissionStore store;

    @Autowired
    private HearingResultSubmissionRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void find_existing_should_return_the_row_recorded_as_sending() {
        final Ids ids = new Ids();
        assertThat(store.findExisting(ids.hearing, ids.prosecutionCase, ids.defendant)).isEmpty();

        store.recordSending(ids.hearing, ids.prosecutionCase, ids.defendant, "E012345678", SHARED, "{}");

        assertThat(store.findExisting(ids.hearing, ids.prosecutionCase, ids.defendant))
                .hasValueSatisfying(existing -> {
                    assertThat(existing.status()).isEqualTo(SubmissionStatus.SENDING);
                    assertThat(store.isStale(existing)).isFalse(); // a call that may still be in flight
                });
    }

    @Test
    void duplicate_record_sending_should_return_empty_instead_of_throwing() {
        final Ids ids = new Ids();
        final Optional<UUID> first = store.recordSending(ids.hearing, ids.prosecutionCase, ids.defendant, "E012345678", SHARED, "{}");

        final Optional<UUID> second = store.recordSending(ids.hearing, ids.prosecutionCase, ids.defendant, "E012345678", SHARED, "{}");

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void final_status_should_never_be_stale_whatever_its_age() {
        final Ids ids = new Ids();
        insert(ids, SubmissionStatus.SUCCEEDED, Instant.now().minus(Duration.ofDays(3)));

        assertThat(store.findExisting(ids.hearing, ids.prosecutionCase, ids.defendant))
                .hasValueSatisfying(existing -> assertThat(store.isStale(existing)).isFalse());
    }

    @Test
    void sending_row_older_than_the_threshold_should_be_stale() {
        final Ids ids = new Ids();
        insert(ids, SubmissionStatus.SENDING, Instant.now().minus(Duration.ofMinutes(6))); // threshold is 5 min

        assertThat(store.findExisting(ids.hearing, ids.prosecutionCase, ids.defendant))
                .hasValueSatisfying(existing -> {
                    assertThat(existing.status()).isEqualTo(SubmissionStatus.SENDING);
                    assertThat(store.isStale(existing)).isTrue();
                });
    }

    @Test
    void record_failed_should_set_status_http_status_and_detail() {
        final Ids ids = new Ids();
        final UUID id = store.recordSending(ids.hearing, ids.prosecutionCase, ids.defendant, "E012345678", SHARED, "{}").orElseThrow();

        store.recordFailed(id, 502, "{\"error\":\"LIBRA_CALL_FAILED\"}");

        final HearingResultSubmissionEntity row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SubmissionStatus.FAILED);
        assertThat(row.getHttpStatus()).isEqualTo(502);
        assertThat(row.getErrorDetail()).contains("LIBRA_CALL_FAILED");
    }

    @Test
    void record_succeeded_should_store_a_json_reply_as_it_is() {
        final UUID id = sendingRow();

        store.recordSucceeded(id, "{\"caseUrn\":\"E012345678\",\"nowsDataItems\":{}}", 200);

        final HearingResultSubmissionEntity row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
        assertThat(row.getResponsePayload()).contains("\"caseUrn\"", "\"nowsDataItems\"");
    }

    // R24: a 2xx is a success even when the reply isn't JSON; response_payload is jsonb, so the raw text is kept as a JSON string
    @Test
    void record_succeeded_should_keep_a_reply_that_is_not_json_as_a_json_string() {
        final UUID id = sendingRow();

        store.recordSucceeded(id, "not json", 200);

        final HearingResultSubmissionEntity row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
        assertThat(row.getHttpStatus()).isEqualTo(200);
        assertThat(row.getResponsePayload()).isEqualTo("\"not json\"");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void record_succeeded_without_a_reply_body_should_store_no_payload(final String body) {
        final UUID id = sendingRow();

        store.recordSucceeded(id, body, 200);

        final HearingResultSubmissionEntity row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
        assertThat(row.getResponsePayload()).isNull();
    }

    @Test
    void record_mapping_failed_should_insert_row_without_request_payload() {
        final Ids ids = new Ids();

        store.recordMappingFailed(ids.hearing, ids.prosecutionCase, ids.defendant, "E012345678", SHARED,
                MappingFailureReason.PROSECUTOR_DEFENDANT_ID_MISSING, "defendant prosecutionAuthorityReference blank");

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.MAPPING_FAILED);
            assertThat(row.getRequestPayload()).isNull();
            assertThat(row.getErrorDetail()).startsWith("PROSECUTOR_DEFENDANT_ID_MISSING");
        });
    }

    @Test
    void record_mapping_failed_should_not_store_an_invalid_case_urn() {
        final Ids ids = new Ids();

        store.recordMappingFailed(ids.hearing, ids.prosecutionCase, ids.defendant, "E".repeat(40), SHARED,
                MappingFailureReason.CASE_URN_INVALID, "caseUrn blank or longer than 36");

        assertThat(repository.findAll()).singleElement().satisfies(row -> assertThat(row.getCaseUrn()).isEmpty());
    }

    @Test
    void record_skipped_no_result_code_should_list_dropped_codes() {
        final Ids ids = new Ids();

        store.recordSkippedNoResultCode(ids.hearing, ids.prosecutionCase, ids.defendant, "E012345678", SHARED, List.of("PGPAY"));

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.SKIPPED_NO_RESULT_CODE);
            assertThat(row.getRequestPayload()).isNull();
            assertThat(row.getErrorDetail()).contains("PGPAY");
        });
    }

    @Test
    void find_stale_sending_should_return_only_old_sending_rows() {
        final Ids stale = new Ids();
        final Ids fresh = new Ids();
        final Ids oldFinal = new Ids();
        insert(stale, SubmissionStatus.SENDING, Instant.now().minus(Duration.ofMinutes(10)));
        insert(fresh, SubmissionStatus.SENDING, Instant.now());
        insert(oldFinal, SubmissionStatus.SUCCEEDED, Instant.now().minus(Duration.ofMinutes(10)));

        final List<UUID> ids = store.findStaleSending(Instant.now().minus(Duration.ofMinutes(5)));

        assertThat(ids).containsExactly(store.findExisting(stale.hearing, stale.prosecutionCase, stale.defendant).orElseThrow().id());
    }

    @Test
    void mark_interrupted_should_fail_a_sending_row_with_unknown_outcome() {
        final Ids ids = new Ids();
        insert(ids, SubmissionStatus.SENDING, Instant.now().minus(Duration.ofMinutes(10)));
        final UUID id = store.findExisting(ids.hearing, ids.prosecutionCase, ids.defendant).orElseThrow().id();

        store.markInterrupted(id);

        final HearingResultSubmissionEntity row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SubmissionStatus.FAILED);
        assertThat(row.getErrorDetail()).isEqualTo("INTERRUPTED – outcome at GOB unknown");
    }

    @Test
    void mark_interrupted_should_not_touch_a_final_row() {
        final Ids ids = new Ids();
        insert(ids, SubmissionStatus.SUCCEEDED, Instant.now().minus(Duration.ofMinutes(10)));
        final UUID id = store.findExisting(ids.hearing, ids.prosecutionCase, ids.defendant).orElseThrow().id();

        store.markInterrupted(id);

        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.SUCCEEDED);
    }

    @Test
    void record_not_sent_should_insert_failed_row_with_reason_and_no_payload() {
        final Ids ids = new Ids();

        store.recordNotSent(ids.hearing, ids.prosecutionCase, ids.defendant, "E012345678", SHARED, "reference data unavailable (X)");

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(SubmissionStatus.FAILED);
            assertThat(row.getRequestPayload()).isNull();
            assertThat(row.getErrorDetail()).isEqualTo("NOT_SENT – reference data unavailable (X)");
        });
    }

    @Test
    void integrity_violation_other_than_the_unique_key_should_not_be_treated_as_duplicate(final CapturedOutput output) {
        final Ids ids = new Ids();
        final String payload = "{\"defendantDetails\":{\"forename\":\"Edward\",\"nationalInsuranceNumber\":\"NH195839C\"}}";

        assertThatThrownBy(() -> store.recordSending(ids.hearing, ids.prosecutionCase, ids.defendant, "E012345678", null, payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("Edward");
        // Postgres reports the whole failing row, request_payload included; it must not reach the logs (FR-017)
        assertThat(output.getAll()).doesNotContain("Edward", "NH195839C");
    }

    private UUID sendingRow() {
        final Ids ids = new Ids();
        return store.recordSending(ids.hearing, ids.prosecutionCase, ids.defendant, "E012345678", SHARED, "{}").orElseThrow();
    }

    private void insert(final Ids ids, final SubmissionStatus status, final Instant updatedAt) {
        final HearingResultSubmissionEntity entity = new HearingResultSubmissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setHearingId(ids.hearing);
        entity.setCaseId(ids.prosecutionCase);
        entity.setDefendantId(ids.defendant);
        entity.setCaseUrn("E012345678");
        entity.setSharedTime(SHARED);
        entity.setStatus(status);
        entity.setCreatedAt(updatedAt);
        entity.setUpdatedAt(updatedAt);
        repository.saveAndFlush(entity);
    }

    private static final class Ids {
        private final UUID hearing = UUID.randomUUID();
        private final UUID prosecutionCase = UUID.randomUUID();
        private final UUID defendant = UUID.randomUUID();
    }
}
