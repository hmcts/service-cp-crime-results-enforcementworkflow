package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.service.NowsHandoverPayloadBuilder.NowRequest;
import uk.gov.hmcts.cp.support.GobDataEventContract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Feature 003 (FR-012, research R12): the GOB data event payload, GOB data verbatim, valid against hearing-nows' schema. */
class NowsHandoverPayloadBuilderTest {

    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final UUID MASTER_DEFENDANT_ID = UUID.fromString("5a7d2c91-6e3b-4f0a-8c4d-2b9e1f6a3d70");
    private static final List<NowRequest> NOWS = List.of(
            new NowRequest(UUID.fromString("1d2c3b4a-0000-4000-8000-0000000000a1"), MASTER_DEFENDANT_ID),
            new NowRequest(UUID.fromString("1d2c3b4a-0000-4000-8000-0000000000a2"), MASTER_DEFENDANT_ID));
    // GOB's own number formatting must come through untouched (feature 002 research R10)
    private static final String REPLY = """
            {"caseUrn":"E012345678","nowsDataItems":{"accountBalance":125.50,"accountNumber":"26000123A"}}""";

    private final NowsHandoverPayloadBuilder builder = new NowsHandoverPayloadBuilder();

    @Test
    void payload_should_carry_request_ids_account_number_and_gob_data_verbatim() {
        final JsonNode items = NowsHandoverPayloadBuilder.nowsDataItemsOf(REPLY).orElseThrow();

        final ObjectNode payload = builder.build(HEARING_ID, NOWS, "26000123A", items, false, Instant.parse("2026-05-03T14:30:00Z"));

        final String json = NowsHandoverPayloadBuilder.write(payload);
        assertThat(payload.path("hearingId").asString()).isEqualTo(HEARING_ID.toString());
        assertThat(payload.path("nows").get(1).path("requestId").asString()).isEqualTo("1d2c3b4a-0000-4000-8000-0000000000a2");
        assertThat(payload.path("nows").get(0).path("masterDefendantId").asString()).isEqualTo(MASTER_DEFENDANT_ID.toString());
        assertThat(payload.path("accountNumber").asString()).isEqualTo("26000123A");
        assertThat(payload.path("gobDataReused").asBoolean()).isFalse();
        assertThat(payload.path("sharedTime").asString()).isEqualTo("2026-05-03T14:30:00Z");
        assertThat(json).contains("\"nowsDataItems\":{\"accountBalance\":125.50,\"accountNumber\":\"26000123A\"}");
        assertThat(GobDataEventContract.violations(json)).isEmpty();
    }

    @Test
    void reply_without_nows_data_items_should_publish_an_empty_object() {
        final JsonNode items = NowsHandoverPayloadBuilder.nowsDataItemsOf("{\"caseUrn\":\"E012345678\"}").orElseThrow();

        final ObjectNode payload = builder.build(HEARING_ID, NOWS, "26000123A", items, true, null);

        assertThat(payload.path("nowsDataItems").isEmpty()).isTrue();
        assertThat(payload.has("sharedTime")).isFalse();
        assertThat(GobDataEventContract.violations(NowsHandoverPayloadBuilder.write(payload))).isEmpty();
    }

    @Test
    void reply_with_null_nows_data_items_should_publish_an_empty_object() {
        assertThat(NowsHandoverPayloadBuilder.nowsDataItemsOf("{\"nowsDataItems\":null}").orElseThrow().isEmpty()).isTrue();
    }

    @Test
    void blank_reply_should_have_nothing_to_publish() {
        assertThat(NowsHandoverPayloadBuilder.nowsDataItemsOf("  ")).isEmpty();
    }

    @Test
    void reply_that_is_not_a_json_object_should_have_nothing_to_publish() {
        assertThat(NowsHandoverPayloadBuilder.nowsDataItemsOf("not json")).isEmpty();
        assertThat(NowsHandoverPayloadBuilder.nowsDataItemsOf("\"a json string\"")).isEmpty();
        assertThat(NowsHandoverPayloadBuilder.nowsDataItemsOf(null)).isEmpty();
    }

    @Test
    void contract_should_reject_a_payload_without_request_ids() {
        assertThat(GobDataEventContract.violations("""
                {"hearingId":"04180ff1-99b0-40b7-9929-ca05bdc767d8","nows":[],"accountNumber":"26000123A",\
                "gobDataReused":false,"nowsDataItems":{}}""")).isNotEmpty();
    }
}
