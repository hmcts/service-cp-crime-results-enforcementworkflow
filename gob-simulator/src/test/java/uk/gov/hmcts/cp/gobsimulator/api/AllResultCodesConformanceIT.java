package uk.gov.hmcts.cp.gobsimulator.api;

import java.util.stream.Stream;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import uk.gov.hmcts.cp.gobsimulator.catalogue.CatalogueLoader;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.gobsimulator.api.OpenApiConformance.conformsToSpec;

/**
 * Every resultCode in the bundled v0.4.0 enum must produce a schema-valid response (AC1, AC3,
 * AC4), plus error-path coverage Task 7 introduced but never exercised across the surface.
 *
 * <p>The 42 codes are read from the bundled contract via {@link CatalogueLoader#schemaResultCodes()}
 * — the same parsing path {@link CatalogueLoader#load()} uses for its own startup validation —
 * rather than hand-copied, so a future contract bump needs no edit here. This codebase has been
 * bitten twice already by a hand-typed snapshot of this enum drifting from the bundled contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("gob-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class AllResultCodesConformanceIT {

    /** All twelve NowsDataItemName values — the widest request CP can make. */
    private static final String ALL_ITEMS = """
            { "name": "Defendant Account" }, { "name": "Account History" },
            { "name": "Account Offences and Penalties" }, { "name": "Account Terms to Pay" },
            { "name": "Account Balance" }, { "name": "Account Warrant Number" },
            { "name": "Warrant Contact Details" }, { "name": "Account Bail Amount" },
            { "name": "CT Account Bank Details" }, { "name": "Days Before Release of Warrant" },
            { "name": "Account Number" }, { "name": "Account Date Imposed" }
            """;

    @Resource
    private MockMvc mockMvc;

    /**
     * The bundled contract's full {@code resultCode} enum (42 values in v0.4.0), derived rather
     * than hand-typed — see the class Javadoc.
     */
    static Stream<String> schemaResultCodes() {
        return new CatalogueLoader().schemaResultCodes().stream().sorted();
    }

    @ParameterizedTest(name = "resultCode {0} returns a schema-valid response")
    @MethodSource("schemaResultCodes")
    void every_enum_result_code_returns_a_schema_valid_response(final String resultCode) throws Exception {
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .header("X-Correlation-ID", "conformance-" + resultCode)
                        .content(requestFor(resultCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseUrn").value("E012345678"))
                .andExpect(jsonPath("$.nowsDataItems").isNotEmpty())
                .andExpect(conformsToSpec());
    }

    @ParameterizedTest(name = "resultCode {0} returns every requested entity")
    @MethodSource("schemaResultCodes")
    void every_requested_entity_is_present_whatever_the_code(final String resultCode) throws Exception {
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(requestFor(resultCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nowsDataItems.defendant").exists())
                .andExpect(jsonPath("$.nowsDataItems.offences").exists())
                .andExpect(jsonPath("$.nowsDataItems.terms").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountBalance").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountNumber").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountWarrantNumber").exists())
                .andExpect(jsonPath("$.nowsDataItems.warrantContactDetails").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountBailAmount").exists())
                .andExpect(jsonPath("$.nowsDataItems.ctBankDetails").exists())
                .andExpect(jsonPath("$.nowsDataItems.daysBeforeReleaseWarrant").exists())
                .andExpect(jsonPath("$.nowsDataItems.accountDateImposed").exists())
                .andExpect(jsonPath("$.nowsDataItems.paymentHistory").exists())
                .andExpect(jsonPath("$.nowsDataItems.transactionHistory").exists());
    }

    // --- Error-path coverage (Task 8 correction 4 — absent from the original brief) ---
    //
    // conformsToSpec() validates the request and the response together (decompiled from the
    // matcher during Task 7), so it can only judge a response when the request that triggered it
    // is itself schema-valid. That splits error coverage into two shapes:
    //   - request-valid but business-rejected: assert 400, conformsToSpec(), and the
    //     {errorCode, errorDescription} shape.
    //   - request-invalid (extra property / out-of-enum value / missing required field): assert
    //     400 and the ErrorResponse shape via jsonPath only — conformsToSpec() would fail on the
    //     REQUEST side regardless of how well-shaped the error response is, so it is inapplicable.

    @Test
    void rejects_a_request_with_a_present_but_blank_required_field() throws Exception {
        // courtHearingLocation is present (satisfying the schema's required-property-key check)
        // but blank, so the request stays contract-valid; only @NotBlank trips. This is the
        // "request-valid but business-rejected" shape, so conformsToSpec() is checking exactly
        // what it should here: that the 400 error BODY is contract-valid too.
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(requestFor("SC").replace(
                                "\"courtHearingLocation\": \"B01BH01\"",
                                "\"courtHearingLocation\": \"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(conformsToSpec())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").isNotEmpty());
    }

    @Test
    void rejects_a_request_carrying_a_property_the_contract_does_not_declare() throws Exception {
        // Request-invalid: additionalProperties: false on HearingResultedRequest — no
        // conformsToSpec(), the request itself violates the schema.
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(requestFor("SC").replace(
                                "\"caseUrn\":", "\"unexpectedField\": 1, \"caseUrn\":")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").isNotEmpty());
    }

    // NOTE: the natural "out-of-enum value" example here would be an out-of-enum resultCode
    // (e.g. "NOT-A-REAL-CODE"), but the simulator does not validate resultCode against the
    // schema's enum before it reaches Catalogue.fieldsFor() — an unknown resultCode currently
    // surfaces as a 500 (IllegalArgumentException falling through to
    // GlobalExceptionHandler.handleUnexpectedFailure()), not a 400. That is a real gap, but fixing
    // it means adding resultCode-enum validation to HearingController/GlobalExceptionHandler,
    // which is production code outside this task's authorised scope (field-paths.yaml only) — see
    // the Task 8 report for detail rather than silently patching around it here. The
    // NowsDataItemName enum is validated the same way this bullet describes, so it stands in as
    // the "out-of-enum value" example instead.
    @Test
    void rejects_a_request_naming_an_unknown_nows_data_item() throws Exception {
        // Request-invalid: "Not A Real Entity" is outside the NowsDataItemName enum — no
        // conformsToSpec(), the request itself violates the schema.
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(requestFor("SC").replace(
                                "{ \"name\": \"Defendant Account\" }", "{ \"name\": \"Not A Real Entity\" }")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").value("Unknown NowsDataItemName: Not A Real Entity"));
    }

    @Test
    void rejects_a_request_missing_a_mandatory_top_level_field() throws Exception {
        // Request-invalid: caseUrn is in HearingResultedRequest's own "required" list — no
        // conformsToSpec(), the request itself violates the schema.
        mockMvc.perform(post("/hearing/result")
                        .contentType(APPLICATION_JSON)
                        .content(requestFor("SC").replace("\"caseUrn\": \"E012345678\",\n", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").isNotEmpty())
                .andExpect(jsonPath("$.errorDescription").isNotEmpty());
    }

    // Correction 3: paymentTerms/enforcement carry the schema's own mandatory flags (PaymentTerms
    // requires paymentDueDate/paymentCardRequested/parentToPay; EnforcementDetails requires
    // prisonSentenceIndicator) so this fixture is itself contract-valid — conformsToSpec()
    // validates the request as well as the response. defendantDetails already satisfies its
    // required pair (prosecutorDefendantId, address1).
    private String requestFor(final String resultCode) {
        return """
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
                  "results": [ { "resultCode": "%s" } ],
                  "nowsDataRequest": { "nowsDataItems": [ %s ] }
                }
                """.formatted(resultCode, ALL_ITEMS);
    }
}
