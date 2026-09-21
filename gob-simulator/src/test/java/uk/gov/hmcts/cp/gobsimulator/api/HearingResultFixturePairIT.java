package uk.gov.hmcts.cp.gobsimulator.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.gobsimulator.api.OpenApiConformance.conformsToSpec;

/**
 * Replays every recorded request/response pair against the live endpoint.
 *
 * <p>A pair is a directory under {@value #FIXTURES_ROOT} holding {@code request.json} and
 * {@code expected-response.json}. <strong>Adding a pair is a drop-in: create the folder, drop the
 * two files, and this test picks it up — no Java edit.</strong> That is the whole point of the
 * harness, so keep it data-driven rather than adding a hand-written method per pair.
 *
 * <p>Three deliberate comparison rules:
 * <ul>
 *   <li>{@code timestamp} is excluded. The endpoint emits {@code Instant.now()} and nothing in the
 *       request supplies a time of day, so a recorded timestamp is a captured moment, not a
 *       contract. Every other field is compared, strictly — a field the endpoint emits but the
 *       fixture does not record is a failure, and vice versa.</li>
 *   <li>Numbers compare by value, which JSONAssert does natively: a fixture recording {@code 340}
 *       matches an emitted {@code 340.00}. The two-decimal money scale
 *       ({@code ValueResolver.MONEY_SCALE}) is deliberate and not what these pairs are pinning.</li>
 *   <li>A {@code correlationId} in the expected body is sent as the {@code X-Correlation-ID}
 *       header, so each fixture stays self-describing — the recorded response says what request
 *       produced it.</li>
 * </ul>
 *
 * <p>Each pair is also asserted against the bundled contract, so no fixture can quietly record a
 * response the schema forbids.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("gob-simulator")
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert") // MockMvc andExpect() calls are assertions
class HearingResultFixturePairIT {

    private static final String FIXTURES_ROOT = "/gob-simulator/fixtures";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private MockMvc mockMvc;

    /** A genuine token from /auth/token — the hearing endpoints reject anything else (ADR-004). */
    private String authorization;

    @BeforeEach
    void obtainBearerToken() throws Exception {
        authorization = BearerTokens.authorizationHeader(mockMvc);
    }

    static Stream<Arguments> recordedPairs() throws Exception {
        final Path root = Path.of(HearingResultFixturePairIT.class.getResource(FIXTURES_ROOT).toURI());
        try (Stream<Path> entries = Files.list(root)) {
            final List<Arguments> pairs = entries
                    .filter(Files::isDirectory)
                    .sorted()
                    .map(dir -> Arguments.of(dir.getFileName().toString(), dir))
                    .toList();
            return pairs.stream();
        }
    }

    // A harness that silently tests nothing when the fixtures fail to resolve is worse than no
    // harness, so the discovery itself is asserted rather than assumed.
    @ParameterizedTest(name = "{0}")
    @MethodSource("recordedPairs")
    void reproduces_the_recorded_response(final String pairName, final Path pairDir) throws Exception {
        final String request = Files.readString(pairDir.resolve("request.json"), UTF_8);
        final JsonNode expected = MAPPER.readTree(pairDir.resolve("expected-response.json").toFile());
        assertThat(request).as("fixture %s has a request", pairName).isNotBlank();

        MockHttpServletRequestBuilder call = post("/hearing/result").header(AUTHORIZATION, authorization)
                .contentType(APPLICATION_JSON)
                .content(request);
        final JsonNode correlationId = expected.get("correlationId");
        if (correlationId != null && !correlationId.isNull()) {
            call = call.header("X-Correlation-ID", correlationId.asText());
        }

        final String actual = mockMvc.perform(call)
                .andExpect(status().isOk())
                .andExpect(conformsToSpec())
                .andReturn().getResponse().getContentAsString();

        JSONAssert.assertEquals(
                withoutTimestamp(expected).toString(),
                withoutTimestamp(MAPPER.readTree(actual)).toString(),
                JSONCompareMode.STRICT);
    }

    /**
     * AC4: "amounts numeric with 2 decimal places".
     *
     * <p>Deliberately asserted against the raw response TEXT, not a parsed tree. Scale is a
     * property of the bytes CP receives, and every JSON parser in the path has an opinion that
     * destroys it — Jackson reads a float as a {@code double} unless told otherwise, and even with
     * {@code USE_BIG_DECIMAL_FOR_FLOATS} the default node factory calls {@code stripTrailingZeros()}.
     * Both of those made an earlier version of this assertion measure its own parser and report
     * every amount as malformed. A regex over the body cannot be fooled that way.
     *
     * <p>This earns its place because {@code ValueResolver} coerces only values it resolves from a
     * catalogue row, and a seed can contribute an amount no row covers — a second or third
     * imposition, since every catalogue path is pinned to index [0] — which reaches the response
     * exactly as the seed file authored it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("recordedPairs")
    void returns_every_amount_at_two_decimal_places(final String pairName, final Path pairDir) throws Exception {
        final String request = Files.readString(pairDir.resolve("request.json"), UTF_8);

        final String body = mockMvc.perform(post("/hearing/result").header(AUTHORIZATION, authorization)
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        final Matcher amounts = AMOUNT_IN_BODY.matcher(body);
        final List<String> malformed = new ArrayList<>();
        while (amounts.find()) {
            final String literal = amounts.group(2);
            final int decimals = literal.indexOf('.') < 0 ? 0 : literal.length() - literal.indexOf('.') - 1;
            if (decimals != 2) {
                malformed.add(amounts.group(1) + " = " + literal);
            }
        }

        assertThat(malformed)
                .as("fixture %s — amounts not at 2 decimal places (AC4)", pairName)
                .isEmpty();
    }

    /** The monetary properties of NowsDataItems, per the bundled contract, as they appear on the wire. */
    private static final Pattern AMOUNT_IN_BODY = Pattern.compile(
            "\"(accountBalance|accountBailAmount|accountTotal|accountPaid"
                    + "|offenceTotal|amountImposed|amountPaid|impositionBalance)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");

    private static JsonNode withoutTimestamp(final JsonNode body) {
        return ((ObjectNode) body.deepCopy()).without("timestamp");
    }
}
