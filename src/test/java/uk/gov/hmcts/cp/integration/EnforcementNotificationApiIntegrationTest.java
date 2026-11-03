package uk.gov.hmcts.cp.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import uk.gov.hmcts.cp.entity.EnforcementNotificationEntity;
import uk.gov.hmcts.cp.entity.NotificationStatus;
import uk.gov.hmcts.cp.support.Fixtures;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.support.TempDiagnosticsLogFilter.withoutTempDiagnostics;

/**
 * Feature 003 (CLAUDE.md: an IT per new endpoint): {@code POST /enforcement-notifications}, every response of the
 * contract. The embedded-broker context is shared with the scenario suite; stage 1 runs in the background, but the Results
 * query is not stubbed for these hearings (WireMock answers 404, i.e. results unavailable), so nothing reaches GOB.
 */
@ExtendWith(OutputCaptureExtension.class)
class EnforcementNotificationApiIntegrationTest extends EmbeddedBrokerIntegrationTestBase {

    private static final UUID HEARING_ID = UUID.fromString("6f1c2b9e-0000-4000-8000-000000000001");
    private static final UUID MASTER_DEFENDANT_ID = UUID.fromString("9a8b7c6d-0000-4000-8000-0000000000d1");

    @Test
    void valid_notification_should_be_accepted_stored_and_queued(final CapturedOutput output) throws Exception {
        final UUID requestId = UUID.randomUUID();
        final long jobsBefore = jobsRepository.count();

        final String response = notify(FUNCTION_APP_USER, body(requestId)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationId").exists()).andReturn().getResponse().getContentAsString();

        final UUID notificationId = UUID.fromString(Fixtures.MAPPER.readTree(response).path("notificationId").asString());
        assertThat(notification(notificationId).getHearingId()).isEqualTo(HEARING_ID);
        // stage 1 is queued in the same transaction; it keeps retrying (results unavailable: 404), so its job is still there
        assertThat(jobsRepository.count()).isEqualTo(jobsBefore + 1);
        assertThat(notification(notificationId).getStatus()).isIn(NotificationStatus.RECEIVED, NotificationStatus.PROCESSING);
        assertThat(notificationNowRepository.findByNotificationId(notificationId)).singleElement()
                .satisfies(now -> assertThat(now.getRequestId()).isEqualTo(requestId));
        // ids only: the request body is never logged
        assertThat(withoutTempDiagnostics(output.getAll())).contains(notificationId.toString()).doesNotContain("\"nows\"").doesNotContain("\"hearingDate\"");
    }

    @Test
    void repeated_notification_should_return_the_same_id_and_queue_nothing_new() throws Exception {
        final String body = body(UUID.randomUUID());
        final String first = notify(FUNCTION_APP_USER, body).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        final long notificationsBefore = notificationRepository.count();
        final long jobsBefore = jobsRepository.count();

        final String second = notify(FUNCTION_APP_USER, body).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(notificationRepository.count()).isEqualTo(notificationsBefore);
        assertThat(jobsRepository.count()).isEqualTo(jobsBefore);
    }

    @Test
    void partly_known_request_ids_should_be_a_conflict() throws Exception {
        final UUID known = UUID.randomUUID();
        notify(FUNCTION_APP_USER, body(known)).andExpect(status().isAccepted());
        final long[] before = rowCounts();

        notify(FUNCTION_APP_USER, body(known, UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        assertThat(rowCounts()).containsExactly(before);
    }

    // FR-011: a repeat is the same hearing, hearing date, reshare flag and request ids (with their master defendants)
    @Test
    void known_request_ids_for_another_hearing_should_be_a_conflict() throws Exception {
        final UUID requestId = UUID.randomUUID();
        notify(FUNCTION_APP_USER, body(requestId)).andExpect(status().isAccepted());
        final long[] before = rowCounts();

        notify(FUNCTION_APP_USER, body(requestId).replace(HEARING_ID.toString(), UUID.randomUUID().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CONFLICT"));

        assertThat(rowCounts()).containsExactly(before);
    }

    // FR-002 (2026-10-22): the reshare flag is required; a repeat must carry the same flag (FR-011)
    @Test
    void missing_reshare_flag_should_be_rejected_and_another_flag_for_known_ids_a_conflict() throws Exception {
        final UUID requestId = UUID.randomUUID();
        notify(FUNCTION_APP_USER, body(requestId).replace("\"isReshare\":false,", "")).andExpect(status().isBadRequest());
        notify(FUNCTION_APP_USER, body(requestId)).andExpect(status().isAccepted());
        final long[] before = rowCounts();

        notify(FUNCTION_APP_USER, body(requestId).replace("\"isReshare\":false", "\"isReshare\":true")).andExpect(status().isConflict());

        assertThat(rowCounts()).containsExactly(before);
    }

    // FR-011: a repeat must match exactly; a subset of the earlier request ids is not a repeat
    @Test
    void subset_of_earlier_request_ids_should_be_a_conflict() throws Exception {
        final UUID first = UUID.randomUUID();
        notify(FUNCTION_APP_USER, body(first, UUID.randomUUID())).andExpect(status().isAccepted());
        final long[] before = rowCounts();

        notify(FUNCTION_APP_USER, body(first)).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CONFLICT"));

        assertThat(rowCounts()).containsExactly(before);
    }

    @Test
    void accepted_reshare_should_store_its_flag_hearing_date_and_caller() throws Exception {
        final UUID requestId = UUID.randomUUID();
        final String response = notify(FUNCTION_APP_USER, body(requestId).replace("\"isReshare\":false", "\"isReshare\":true"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        final EnforcementNotificationEntity stored = notification(UUID.fromString(Fixtures.MAPPER.readTree(response).path("notificationId").asString()));
        assertThat(stored.isReshare()).isTrue();
        assertThat(stored.getHearingDate()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(stored.getCaller()).isEqualTo(FUNCTION_APP_USER);
        assertThat(notificationNowRepository.findByNotificationId(stored.getId())).singleElement()
                .satisfies(now -> assertThat(now.getMasterDefendantId()).isEqualTo(MASTER_DEFENDANT_ID));
    }

    // the contract allows 1 to 50 NOW requests
    @Test
    void more_than_fifty_now_requests_should_be_rejected() throws Exception {
        final long[] before = rowCounts();
        final UUID[] requestIds = new UUID[51];
        for (int i = 0; i < requestIds.length; i++) {
            requestIds[i] = UUID.randomUUID();
        }

        notify(FUNCTION_APP_USER, body(requestIds)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID"));

        assertThat(rowCounts()).containsExactly(before);
    }

    @Test
    void invalid_hearing_date_or_master_defendant_id_should_be_rejected() throws Exception {
        final long[] before = rowCounts();

        notify(FUNCTION_APP_USER, body(UUID.randomUUID()).replace("2026-10-19", "19/10/2026")).andExpect(status().isBadRequest());
        notify(FUNCTION_APP_USER, body(UUID.randomUUID()).replace("2026-10-19", "2026-13-45")).andExpect(status().isBadRequest());
        notify(FUNCTION_APP_USER, body(UUID.randomUUID()).replace(MASTER_DEFENDANT_ID.toString(), "not-a-uuid"))
                .andExpect(status().isBadRequest());

        assertThat(rowCounts()).containsExactly(before);
    }

    @Test
    void invalid_body_should_be_rejected() throws Exception {
        final long[] before = rowCounts();
        notify(FUNCTION_APP_USER, "{\"hearingId\":\"" + HEARING_ID + "\",\"hearingDate\":\"2026-10-19\",\"isReshare\":false,\"nows\":[]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID"));
        notify(FUNCTION_APP_USER, "{\"hearingId\":\"not-a-uuid\"}").andExpect(status().isBadRequest());
        final UUID repeated = UUID.randomUUID();
        notify(FUNCTION_APP_USER, body(repeated, repeated)).andExpect(status().isBadRequest());

        assertThat(rowCounts()).containsExactly(before);
    }

    @Test
    void unknown_missing_or_malformed_system_user_should_be_forbidden() throws Exception {
        final long[] before = rowCounts();
        notify(UUID.randomUUID().toString(), body(UUID.randomUUID())).andExpect(status().isForbidden());
        notify("not-a-uuid", body(UUID.randomUUID())).andExpect(status().isForbidden());
        mockMvc.perform(post("/enforcement-notifications").contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        assertThat(rowCounts()).containsExactly(before);
    }

    /** Notifications, notification NOWs and jobs: a rejected notification stores and queues nothing. */
    private long[] rowCounts() {
        return new long[] {notificationRepository.count(), notificationNowRepository.count(), jobsRepository.count()};
    }

    private ResultActions notify(final String caller, final String body) throws Exception {
        return mockMvc.perform(post("/enforcement-notifications").contentType(MediaType.APPLICATION_JSON)
                .header("CJSCPPUID", caller).content(body));
    }

    private static String body(final UUID... requestIds) {
        final StringBuilder nows = new StringBuilder();
        for (final UUID requestId : requestIds) {
            nows.append(nows.isEmpty() ? "" : ",").append("{\"requestId\":\"").append(requestId)
                    .append("\",\"masterDefendantId\":\"").append(MASTER_DEFENDANT_ID).append("\"}");
        }
        return "{\"hearingId\":\"" + HEARING_ID + "\",\"hearingDate\":\"2026-10-19\",\"isReshare\":false,\"nows\":[" + nows + "]}";
    }
}
