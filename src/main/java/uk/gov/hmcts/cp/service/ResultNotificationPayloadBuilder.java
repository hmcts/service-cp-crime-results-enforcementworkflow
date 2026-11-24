package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.Area;
import uk.gov.hmcts.cp.entity.NotificationType;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;
import uk.gov.hmcts.cp.event.HearingResultedEvent.JudicialResultPrompt;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Offence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the per-offence {@code result_short_codes} JSON (data-model.md, research.md R7) and the System Doc
 * Generator payload (research.md R7, "current + exactly one previous, never full history"). Only trigger-list
 * shortCodes are rendered (FR-004 - "every matched trigger shortCode", not every captured shortCode); current and
 * previous are pre-merged and diffed in Java, not in the Docmosis template (templates are hard to unit test).
 */
@Component
@RequiredArgsConstructor
public class ResultNotificationPayloadBuilder {

    private final ResultNotificationProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Groups the defendant's offence-level results by {@code offenceId}, keeping only those whose shortCode is in the
     * trigger list for {@code area} (FR-004), in the order {@link ResolvedCodes#results()} carries them (capture order).
     */
    public String buildResultShortCodes(final Defendant defendant, final ResolvedCodes resolved, final Area area) {
        final Map<UUID, String> resultLabelsByResultId = resultLabelsByResultId(defendant);
        final Map<UUID, Offence> offencesById = offencesById(defendant);
        final Map<UUID, List<ShortCodeEntry>> byOffence = new LinkedHashMap<>();
        for (final ResolvedResult result : resolved.results()) {
            if (result.offenceId() != null && properties.isTriggerShortCode(area, result.cpShortCode())) {
                byOffence.computeIfAbsent(result.offenceId(), k -> new ArrayList<>())
                        .add(new ShortCodeEntry(result.judicialResultId(), result.cpShortCode(),
                                resultLabelsByResultId.get(result.judicialResultId()), result.orderedDate(), result.prompts()));
            }
        }
        final List<OffenceShortCodes> offences = byOffence.entrySet().stream()
                .map(e -> toOffenceShortCodes(offencesById.get(e.getKey()), e.getKey(), e.getValue()))
                .toList();
        return objectMapper.writeValueAsString(offences);
    }

    /** FIRST_SHARE: current state only - omits the whole previous/changed concept, not merely leaves it null (research.md R7). */
    public String buildDocGenPayload(final NotificationType notificationType, final UUID hearingId, final LocalDate hearingDate,
                                     final String caseUrn, final String relatedReferenceNumber, final String currentResultShortCodesJson) {
        final List<Map<String, Object>> offencePayloads = parseOffences(currentResultShortCodesJson).stream()
                .map(offence -> {
                    final Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("offenceId", offence.offenceId().toString());
                    entry.put("offenceTitle", offence.offenceTitle());
                    entry.put("offenceDetails", offence.offenceDetails());
                    entry.put("current", Map.of("shortCodes", offence.shortCodes()));
                    return entry;
                })
                .toList();
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationType", notificationType.name());
        payload.put("hearingId", hearingId.toString());
        payload.put("hearingDate", hearingDate.toString());
        payload.put("caseUrn", caseUrn);
        payload.put("relatedReferenceNumber", relatedReferenceNumber);
        payload.put("offences", offencePayloads);
        return objectMapper.writeValueAsString(payload);
    }

    /** AMEND_RESHARE: current + previous per offence, plus a computed {@code changed} flag and {@code amendmentDate} (plan.md D9). */
    public String buildDocGenPayload(final NotificationType notificationType, final UUID hearingId, final LocalDate hearingDate,
                                     final String caseUrn, final String relatedReferenceNumber, final String currentResultShortCodesJson,
                                     final String previousResultShortCodesJson, final Instant amendmentDate) {
        final List<OffenceShortCodes> current = parseOffences(currentResultShortCodesJson);
        final Map<UUID, OffenceShortCodes> previousByOffence = parseOffences(previousResultShortCodesJson).stream()
                .collect(LinkedHashMap::new, (m, o) -> m.put(o.offenceId(), o), Map::putAll);
        final List<Map<String, Object>> offencePayloads = new ArrayList<>();
        final Map<UUID, Boolean> seen = new LinkedHashMap<>();
        for (final OffenceShortCodes offence : current) {
            seen.put(offence.offenceId(), true);
            // AMEND_RESHARE always carries the previous/changed concept, even with no previous SENT row at all
            // (research.md R9: a reshare whose first share was suppressed) - every offence is then previous:null,
            // changed:true, never rendered as a bare FIRST_SHARE-shaped offence.
            offencePayloads.add(offencePayload(offence, previousByOffence.get(offence.offenceId())));
        }
        for (final OffenceShortCodes previous : previousByOffence.values()) {
            if (!seen.containsKey(previous.offenceId())) {
                offencePayloads.add(offencePayloadRemoved(previous));
            }
        }
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationType", notificationType.name());
        payload.put("hearingId", hearingId.toString());
        payload.put("hearingDate", hearingDate.toString());
        payload.put("caseUrn", caseUrn);
        payload.put("relatedReferenceNumber", relatedReferenceNumber);
        if (amendmentDate != null) {
            payload.put("amendmentDate", amendmentDate.toString());
        }
        payload.put("offences", offencePayloads);
        return objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> offencePayload(final OffenceShortCodes current, final OffenceShortCodes previous) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("offenceId", current.offenceId().toString());
        entry.put("offenceTitle", current.offenceTitle());
        entry.put("offenceDetails", current.offenceDetails());
        entry.put("current", Map.of("shortCodes", current.shortCodes()));
        entry.put("previous", previous == null ? null : Map.of("shortCodes", previous.shortCodes()));
        entry.put("changed", previous == null || !previous.shortCodes().equals(current.shortCodes()));
        return entry;
    }

    private Map<String, Object> offencePayloadRemoved(final OffenceShortCodes previous) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("offenceId", previous.offenceId().toString());
        entry.put("offenceTitle", previous.offenceTitle());
        entry.put("offenceDetails", previous.offenceDetails());
        entry.put("current", null);
        entry.put("previous", Map.of("shortCodes", previous.shortCodes()));
        entry.put("changed", true);
        return entry;
    }

    private List<OffenceShortCodes> parseOffences(final String json) {
        return json == null ? List.of() : List.of(objectMapper.readValue(json, OffenceShortCodes[].class));
    }

    private static OffenceShortCodes toOffenceShortCodes(final Offence offence, final UUID offenceId, final List<ShortCodeEntry> shortCodes) {
        return new OffenceShortCodes(offenceId, offence == null ? null : offence.offenceTitle(),
                offence == null ? null : offence.offenceDetails(), shortCodes);
    }

    private static Map<UUID, String> resultLabelsByResultId(final Defendant defendant) {
        final Map<UUID, String> labels = new LinkedHashMap<>();
        safe(defendant.offences()).forEach(offence -> safe(offence.judicialResults())
                .forEach(r -> labels.put(r.judicialResultId(), r.resultLabel())));
        return labels;
    }

    private static Map<UUID, Offence> offencesById(final Defendant defendant) {
        final Map<UUID, Offence> offences = new LinkedHashMap<>();
        safe(defendant.offences()).forEach(offence -> offences.put(offence.id(), offence));
        return offences;
    }

    private static <T> List<T> safe(final List<T> list) {
        return list != null ? list : List.of();
    }

    /** One offence group within {@code result_short_codes} / the doc-gen payload (data-model.md). */
    public record OffenceShortCodes(UUID offenceId, String offenceTitle, String offenceDetails, List<ShortCodeEntry> shortCodes) {
    }

    /** One trigger-list shortCode within an offence group (data-model.md). */
    public record ShortCodeEntry(UUID judicialResultId, String shortCode, String resultLabel, LocalDate orderedDate,
                                 List<JudicialResultPrompt> prompts) {
    }
}
