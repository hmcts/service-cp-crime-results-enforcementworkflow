package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.client.HandoverResult;
import uk.gov.hmcts.cp.client.HearingNowsClient;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.entity.NowsHandoverStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Feature 002 FR-001 to FR-004: the hand-over outcome is recorded; a failure is logged without GOB data. */
@ExtendWith(OutputCaptureExtension.class)
class NowsHandoverTest {

    private static final UUID ROW = UUID.randomUUID();
    private static final SubmissionKey KEY = new SubmissionKey(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-05-03"),
            UUID.randomUUID(), UUID.randomUUID(), "E012345678", Instant.parse("2026-05-03T14:30:00Z"));

    private final HearingNowsClient client = mock(HearingNowsClient.class);
    private final SubmissionStore store = mock(SubmissionStore.class);
    private final NowsHandover handover = new NowsHandover(new NowsHandoverPayloadBuilder(), client, store);

    @Test
    void accepted_hand_over_should_be_recorded_as_handed_over() {
        when(client.handOver(anyString())).thenReturn(new HandoverResult.HandedOver());

        handover.handOver(ROW, KEY, UUID.randomUUID(), "26000123A", items(), false, List.of(), null);

        verify(store).recordHandover(ROW, NowsHandoverStatus.HANDED_OVER, null);
    }

    @Test
    void failed_hand_over_should_be_recorded_with_the_detail_and_logged_without_gob_data(final CapturedOutput output) {
        when(client.handOver(anyString())).thenReturn(new HandoverResult.Failed("HTTP 403"));

        handover.handOver(ROW, KEY, UUID.randomUUID(), "26000123A", items(), true, List.of(), "reshare");

        verify(store).recordHandover(ROW, NowsHandoverStatus.FAILED, "reshare: HTTP 403");
        assertThat(output.getAll()).contains("hand-over to hearing NOWS failed: HTTP 403").doesNotContain("26000123A", "125.5");
    }

    @Test
    void missing_master_defendant_id_should_be_recorded_as_failed_without_calling_hearing_nows() {
        handover.handOver(ROW, KEY, null, "26000123A", items(), false, List.of(), null);

        verify(store).recordHandover(ROW, NowsHandoverStatus.FAILED, "no masterDefendantId");
        verifyNoInteractions(client);
    }

    private static JsonNode items() {
        return PayloadJson.MAPPER.readTree("{\"accountBalance\":125.5}");
    }
}
