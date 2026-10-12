package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.config.PayloadJson;
import uk.gov.hmcts.cp.support.HearingNowsContract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Feature 002 T038: the hand-over payload, GOB data verbatim, valid against hearing-nows' schema (constitution II v1.1.1). */
class NowsHandoverPayloadBuilderTest {

    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-05-03");
    private static final SubmissionKey KEY = new SubmissionKey(ShareCorrelationId.forShare(HEARING_ID, HEARING_DAY), HEARING_ID, HEARING_DAY,
            UUID.fromString("861aaa70-f142-469d-a7db-9af395347dd5"), UUID.fromString("0ddbc4a7-3c85-4bc3-bdd5-843817320b98"),
            "E012345678", Instant.parse("2026-05-03T14:30:00Z"));
    private static final UUID MASTER_DEFENDANT_ID = UUID.fromString("0ddbc4a7-3c85-4bc3-bdd5-843817320b98");
    private static final UUID SC_TYPE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    // GOB's own number formatting must come through untouched (research R10)
    private static final String REPLY = """
            {"caseUrn":"E012345678","nowsDataItems":{"accountBalance":125.50,"accountNumber":"26000123A"}}""";

    private final NowsHandoverPayloadBuilder builder = new NowsHandoverPayloadBuilder();

    @Test
    void payload_should_carry_ids_share_and_gob_data_verbatim() {
        final JsonNode items = NowsHandoverPayloadBuilder.nowsDataItemsOf(REPLY).orElseThrow();

        final String payload = builder.build(KEY, MASTER_DEFENDANT_ID, "26000123A", items, false, List.of(SC_TYPE));

        final JsonNode json = PayloadJson.MAPPER.readTree(payload);
        assertThat(json.path("correlationId").asString()).isEqualTo("91f9490e-846c-3c3b-b827-1eac49327338");
        assertThat(json.path("hearingId").asString()).isEqualTo(HEARING_ID.toString());
        assertThat(json.path("hearingDay").asString()).isEqualTo("2026-05-03");
        assertThat(json.path("masterDefendantId").asString()).isEqualTo(MASTER_DEFENDANT_ID.toString());
        assertThat(json.path("prosecutionCaseId").asString()).isEqualTo(KEY.caseId().toString());
        assertThat(json.path("caseUrn").asString()).isEqualTo("E012345678");
        assertThat(json.path("accountNumber").asString()).isEqualTo("26000123A");
        assertThat(json.path("sharedTime").asString()).isEqualTo("2026-05-03T14:30:00Z");
        assertThat(json.path("gobDataReused").asBoolean()).isFalse();
        assertThat(json.path("coveredResultDefinitionIds").get(0).asString()).isEqualTo(SC_TYPE.toString());
        assertThat(payload).contains("\"nowsDataItems\":{\"accountBalance\":125.50,\"accountNumber\":\"26000123A\"}");
        assertThat(HearingNowsContract.addEnforcementNowsDataViolations(payload)).isEmpty();
    }

    @Test
    void reply_without_nows_data_items_should_hand_over_an_empty_object() {
        final JsonNode items = NowsHandoverPayloadBuilder.nowsDataItemsOf("{\"caseUrn\":\"E012345678\"}").orElseThrow();

        final String payload = builder.build(KEY, MASTER_DEFENDANT_ID, "26000123A", items, true, List.of());

        assertThat(PayloadJson.MAPPER.readTree(payload).path("nowsDataItems").isEmpty()).isTrue();
        assertThat(HearingNowsContract.addEnforcementNowsDataViolations(payload)).isEmpty();
    }

    @Test
    void reply_that_is_not_a_json_object_should_have_nothing_to_hand_over() {
        assertThat(NowsHandoverPayloadBuilder.nowsDataItemsOf("not json")).isEmpty();
        assertThat(NowsHandoverPayloadBuilder.nowsDataItemsOf("\"a json string\"")).isEmpty();
        assertThat(NowsHandoverPayloadBuilder.nowsDataItemsOf(null)).isEmpty();
    }
}
