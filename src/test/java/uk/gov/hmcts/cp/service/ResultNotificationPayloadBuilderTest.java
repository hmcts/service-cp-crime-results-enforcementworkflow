package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.Area;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;
import uk.gov.hmcts.cp.event.HearingResultedEvent.JudicialResult;
import uk.gov.hmcts.cp.event.HearingResultedEvent.JudicialResultPrompt;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Offence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResultNotificationPayloadBuilderTest {

    private static final UUID OFFENCE_1 = UUID.randomUUID();
    private static final UUID OFFENCE_2 = UUID.randomUUID();
    private static final UUID RESULT_1 = UUID.randomUUID();
    private static final UUID RESULT_2 = UUID.randomUUID();
    private static final UUID RESULT_3 = UUID.randomUUID();
    private static final UUID RESULT_TYPE = UUID.randomUUID();
    private static final LocalDate ORDERED_DATE = LocalDate.parse("2026-11-20");

    private final ResultNotificationProperties properties = new ResultNotificationProperties();
    private final ResultNotificationPayloadBuilder builder =
            new ResultNotificationPayloadBuilder(properties, JsonMapper.builder().build());

    private Defendant defendant() {
        final JudicialResultPrompt prompt = new JudicialResultPrompt("IAMT", "Instalment amount", "£487.13");
        final Offence offence1 = new Offence(OFFENCE_1,
                List.of(new JudicialResult(RESULT_1, RESULT_TYPE, "label", ORDERED_DATE, List.of(prompt), null, null, "Committal warrant")),
                "Theft from a shop", "On 01/01/2031 at a shop...");
        final Offence offence2 = new Offence(OFFENCE_2,
                List.of(new JudicialResult(RESULT_2, RESULT_TYPE, "label", ORDERED_DATE, List.of(), null, null, "Non-trigger result")),
                "Criminal damage", "On 02/01/2031...");
        return new Defendant(UUID.randomUUID(), UUID.randomUUID(), "0100104567C", null, null, List.of(), List.of(offence1, offence2));
    }

    private static ResolvedCodes resolvedCodes(final List<ResolvedResult> results) {
        return ResolvedCodes.fromResults(results, List.of());
    }

    @Test
    void should_group_only_trigger_short_codes_by_offence() {
        final ResolvedCodes resolved = resolvedCodes(List.of(
                new ResolvedResult(OFFENCE_1, RESULT_1, RESULT_TYPE, "CW", "CW", false, ORDERED_DATE),
                new ResolvedResult(OFFENCE_2, RESULT_2, RESULT_TYPE, "PTNV", "PTNV", false, ORDERED_DATE)));

        final String json = builder.buildResultShortCodes(defendant(), resolved, Area.ENFORCEMENT);

        assertThat(json).contains("\"offenceId\":\"" + OFFENCE_1 + "\"")
                .contains("\"offenceTitle\":\"Theft from a shop\"")
                .contains("\"shortCode\":\"CW\"")
                .contains("\"resultLabel\":\"Committal warrant\"")
                .doesNotContain(OFFENCE_2.toString()) // PTNV is not a trigger code: offence 2 is excluded entirely
                .doesNotContain("PTNV");
    }

    // ResolvedResult.offenceId() is null for a defendant-case or hearing-level result (feature 002 research R19) -
    // these are never attributed to any offence group, even when the shortCode is a trigger code
    @Test
    void should_exclude_a_trigger_short_code_with_no_offence_id() {
        final ResolvedCodes resolved = resolvedCodes(List.of(new ResolvedResult(null, RESULT_1, RESULT_TYPE, "CW", "CW", false, ORDERED_DATE)));

        final String json = builder.buildResultShortCodes(defendant(), resolved, Area.ENFORCEMENT);

        assertThat(json).isEqualTo("[]");
    }

    // defensive: a ResolvedResult referencing an offenceId absent from defendant.offences() (shouldn't normally
    // happen) still renders a group, just with no offence title/details to show
    @Test
    void should_render_null_offence_title_and_details_when_the_offence_is_unknown() {
        final ResolvedCodes resolved = resolvedCodes(List.of(new ResolvedResult(UUID.randomUUID(), RESULT_1, RESULT_TYPE, "CW", "CW",
                false, ORDERED_DATE)));

        final String json = builder.buildResultShortCodes(defendant(), resolved, Area.ENFORCEMENT);

        assertThat(json).contains("\"offenceTitle\":null").contains("\"offenceDetails\":null");
    }

    @Test
    void should_treat_a_defendant_with_a_null_offences_list_as_having_none() {
        final Defendant defendant = new Defendant(UUID.randomUUID(), UUID.randomUUID(), "0100104567C", null, null, List.of(), null);
        final ResolvedCodes resolved = resolvedCodes(List.of(new ResolvedResult(OFFENCE_1, RESULT_1, RESULT_TYPE, "CW", "CW", false,
                ORDERED_DATE)));

        final String json = builder.buildResultShortCodes(defendant, resolved, Area.ENFORCEMENT);

        assertThat(json).contains("\"offenceTitle\":null");
    }

    @Test
    void should_keep_capture_order_within_an_offence() {
        final ResolvedCodes resolved = resolvedCodes(List.of(
                new ResolvedResult(OFFENCE_1, RESULT_1, RESULT_TYPE, "CW", "CW", false, ORDERED_DATE),
                new ResolvedResult(OFFENCE_1, RESULT_3, RESULT_TYPE, "CWN", "CWN", false, ORDERED_DATE)));

        final String json = builder.buildResultShortCodes(defendant(), resolved, Area.ENFORCEMENT);

        assertThat(json.indexOf("\"CW\"")).isLessThan(json.indexOf("\"CWN\""));
    }

    @Test
    void first_share_payload_should_have_no_previous_or_changed_fields() {
        final String current = singleOffenceShortCodes("CW");

        final String payload = builder.buildDocGenPayload(NotificationType.FIRST_SHARE, UUID.randomUUID(),
                LocalDate.parse("2031-03-07"), "URN123", "0100104567C", current);

        assertThat(payload).contains("\"notificationType\":\"FIRST_SHARE\"").doesNotContain("\"previous\"", "\"changed\"", "amendmentDate");
    }

    @Test
    void amend_reshare_payload_should_mark_an_unchanged_offence_as_not_changed() {
        final String shortCodes = singleOffenceShortCodes("CW");

        final String payload = builder.buildDocGenPayload(NotificationType.AMEND_RESHARE, UUID.randomUUID(),
                LocalDate.parse("2031-03-07"), "URN123", "0100104567C", shortCodes, shortCodes, Instant.parse("2031-04-02T10:00:00Z"));

        assertThat(payload).contains("\"changed\":false").contains("\"amendmentDate\":\"2031-04-02T10:00:00Z\"");
    }

    @Test
    void amend_reshare_payload_with_a_null_amendment_date_should_omit_it() {
        final String shortCodes = singleOffenceShortCodes("CW");

        final String payload = builder.buildDocGenPayload(NotificationType.AMEND_RESHARE, UUID.randomUUID(),
                LocalDate.parse("2031-03-07"), "URN123", "0100104567C", shortCodes, shortCodes, null);

        assertThat(payload).doesNotContain("amendmentDate");
    }

    // distinct from "no previous row at all": here a previous row exists, but its shortCodes genuinely differ
    @Test
    void amend_reshare_payload_should_mark_a_changed_offence_when_shortcodes_differ() {
        final String previous = singleOffenceShortCodes("CW");
        final String current = singleOffenceShortCodes("CWN");

        final String payload = builder.buildDocGenPayload(NotificationType.AMEND_RESHARE, UUID.randomUUID(),
                LocalDate.parse("2031-03-07"), "URN123", "0100104567C", current, previous, Instant.now());

        assertThat(payload).contains("\"changed\":true").contains("\"CWN\"").contains("\"CW\"");
    }

    @Test
    void amend_reshare_payload_should_mark_a_new_offence_as_changed_with_no_previous() {
        final String current = singleOffenceShortCodes("CW");

        final String payload = builder.buildDocGenPayload(NotificationType.AMEND_RESHARE, UUID.randomUUID(),
                LocalDate.parse("2031-03-07"), "URN123", "0100104567C", current, null, Instant.now());

        assertThat(payload).contains("\"previous\":null").contains("\"changed\":true");
    }

    // research.md R9: a reshare whose first share was suppressed has no prior SENT row at all - this is still
    // AMEND_RESHARE-shaped (previous:null, changed:true per offence), never rendered like a bare FIRST_SHARE payload
    @Test
    void amend_reshare_payload_with_no_previous_row_at_all_should_still_carry_previous_and_changed() {
        final String current = singleOffenceShortCodes("CW");

        final String payload = builder.buildDocGenPayload(NotificationType.AMEND_RESHARE, UUID.randomUUID(),
                LocalDate.parse("2031-03-07"), "URN123", "0100104567C", current, null, Instant.parse("2031-04-02T10:00:00Z"));

        assertThat(payload).contains("\"previous\":null").contains("\"changed\":true").contains("\"amendmentDate\"");
    }

    @Test
    void amend_reshare_payload_should_show_an_offence_removed_since_the_previous_share() {
        final String previous = singleOffenceShortCodes("CW");
        final String current = "[]";

        final String payload = builder.buildDocGenPayload(NotificationType.AMEND_RESHARE, UUID.randomUUID(),
                LocalDate.parse("2031-03-07"), "URN123", "0100104567C", current, previous, Instant.now());

        assertThat(payload).contains("\"current\":null").contains("\"changed\":true").contains(OFFENCE_1.toString());
    }

    private String singleOffenceShortCodes(final String shortCode) {
        final ResolvedCodes resolved = resolvedCodes(List.of(new ResolvedResult(OFFENCE_1, RESULT_1, RESULT_TYPE, shortCode,
                shortCode, false, ORDERED_DATE)));
        return builder.buildResultShortCodes(defendant(), resolved, Area.ENFORCEMENT);
    }
}
