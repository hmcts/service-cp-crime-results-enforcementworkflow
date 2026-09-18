package uk.gov.hmcts.cp.gobsimulator.api;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.gobsimulator.api.OpenApiConformance.conformsToSpec;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("gob-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class HearingResultControllerIT {

    // NOTE: paymentTerms/enforcement carry the schema's mandatory flags (PaymentTerms requires
    // paymentDueDate/paymentCardRequested/parentToPay; EnforcementDetails requires
    // prisonSentenceIndicator) so this fixture is itself contract-valid — conformsToSpec()
    // validates the request as well as the response.
    private static final String SC_REQUEST = """
            {
              "caseUrn": "E012345678",
              "dateOfHearing": "2026-05-03",
              "courtHearingLocation": "B01BH01",
              "defendantDetails": {
                "prosecutorDefendantId": "1234567890",
                "address1": "1 Example Street"
              },
              "paymentTerms": {
                "paymentDueDate": "2026-05-31",
                "paymentCardRequested": "N",
                "parentToPay": "N"
              },
              "enforcement": { "prisonSentenceIndicator": "N" },
              "results": [ { "resultCode": "SC" } ],
              "nowsDataRequest": {
                "nowsDataItems": [ { "name": "Account Balance" }, { "name": "Account Number" } ]
              }
            }
            """;

    @Resource
    private MockMvc mockMvc;

    @Test
    void returns_a_schema_valid_response_for_a_suspended_committal() throws Exception {
        mockMvc.perform(post("/hearing/result").contentType(APPLICATION_JSON).content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseUrn").value("E012345678"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.nowsDataItems.accountBalance").value(1250.00))
                .andExpect(jsonPath("$.nowsDataItems.accountNumber").value("ACC0001"))
                .andExpect(conformsToSpec());
    }

    @Test
    void returns_exactly_the_requested_entities_and_nothing_more() throws Exception {
        mockMvc.perform(post("/hearing/result").contentType(APPLICATION_JSON).content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nowsDataItems.length()").value(2))
                .andExpect(jsonPath("$.nowsDataItems.defendant").doesNotExist())
                .andExpect(jsonPath("$.nowsDataItems.terms").doesNotExist());
    }

    @Test
    void echoes_the_correlation_id_when_the_caller_supplies_one() throws Exception {
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Correlation-ID", "9f3d2e42-8d30-4d16-9dd6-6d4e26889d5c")
                        .content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correlationId").value("9f3d2e42-8d30-4d16-9dd6-6d4e26889d5c"));
    }

    @Test
    void omits_the_correlation_id_when_the_caller_supplies_none() throws Exception {
        mockMvc.perform(post("/hearing/result").contentType(APPLICATION_JSON).content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correlationId").doesNotExist());
    }

    @Test
    void returns_an_iso_8601_utc_timestamp() throws Exception {
        final String body = mockMvc.perform(
                        post("/hearing/result").contentType(APPLICATION_JSON).content(SC_REQUEST))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).containsPattern("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z\"");
    }

    @Test
    void repeats_the_same_body_for_the_same_idempotency_key() throws Exception {
        final String first = mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Idempotency-Key", "key-1")
                        .content(SC_REQUEST))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Idempotency-Key", "key-1")
                        .content(SC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(content().json(first, true));
    }

    @Test
    void issues_a_fresh_timestamp_for_a_different_idempotency_key() throws Exception {
        final String first = mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Idempotency-Key", "key-A")
                        .content(SC_REQUEST))
                .andReturn().getResponse().getContentAsString();

        Thread.sleep(1100);

        final String second = mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Idempotency-Key", "key-B")
                        .content(SC_REQUEST))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void never_returns_an_enforcer_code() throws Exception {
        final String body = mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(SC_REQUEST.replace("\"Account Balance\"", "\"Defendant Account\"")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("enforcerCode");
    }

    // NOTE: no conformsToSpec() here — the request is deliberately invalid against the contract
    // (additionalProperties: false), so the harness would fail on the REQUEST side regardless of
    // how well-shaped the error response is. That is true of both this test and the one below;
    // conformsToSpec() only usefully checks an error BODY when the fixture that trips the
    // rejection can itself stay schema-valid (see the fix to
    // HearingControllerIT.rejects_a_confirmation_missing_the_mandatory_court_location).
    @Test
    void rejects_a_request_carrying_a_property_the_contract_does_not_declare() throws Exception {
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(SC_REQUEST.replace("\"caseUrn\":", "\"unexpectedField\": 1, \"caseUrn\":")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").isNotEmpty());
    }

    // Correction 3: an unknown NowsDataItemName must be a 400 (client mistake), not the 500 that
    // Catalogue.propertiesFor() would otherwise surface. (No conformsToSpec() — see the note
    // above; an out-of-enum name is itself a request-side schema violation.)
    @Test
    void rejects_a_request_naming_an_unknown_nows_data_item() throws Exception {
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(SC_REQUEST.replace("\"Account Balance\"", "\"Not A Real Entity\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").value("Unknown NowsDataItemName: Not A Real Entity"));
    }
}
