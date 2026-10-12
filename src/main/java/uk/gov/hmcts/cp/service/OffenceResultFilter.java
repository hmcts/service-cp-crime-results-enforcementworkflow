package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.entity.GobSentOffenceEntity;
import uk.gov.hmcts.cp.repository.GobSentOffenceRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Leaves out what must not be sent to GOB in this share (feature 002 FR-018, research R19, R20):
 * <ul>
 *   <li>an offence already sent in an earlier share or hearing of the case, with all its results, whatever the
 *       hearing-resulted payload repeats;</li>
 *   <li>an offence adjourned in this share: one of its results marks an adjournment ({@code isAdjournmentResult},
 *       a next hearing, or a shortCode in {@code cp.hearing-result.adjournment-result-codes}) and was ordered on the
 *       share's hearing day (an earlier day's adjournment repeated in the payload does not count). It is sent when
 *       finally resulted;</li>
 *   <li>adjournment results themselves, which are never sent;</li>
 *   <li>a result not attached to an offence that was already sent (by {@code judicialResultId}).</li>
 * </ul>
 * The items with at least one GOB code left to send are returned, to be recorded with the submission.
 */
@Component
@RequiredArgsConstructor
public class OffenceResultFilter {

    private final GobSentOffenceRepository sentOffenceRepository;
    private final HearingResultProperties properties;

    public OffenceFilterResult filter(final UUID caseId, final LocalDate hearingDay, final ResolvedCodes codes) {
        final Map<UUID, List<ResolvedResult>> byOffence = codes.results().stream()
                .filter(result -> result.offenceId() != null)
                .collect(Collectors.groupingBy(ResolvedResult::offenceId, LinkedHashMap::new, Collectors.toList()));
        final List<ResolvedResult> nonOffence = codes.results().stream().filter(result -> result.offenceId() == null).toList();
        final Set<UUID> sentOffences = byOffence.isEmpty() ? Set.of()
                : sentOffenceRepository.findByCaseIdAndOffenceIdIn(caseId, byOffence.keySet()).stream()
                .map(GobSentOffenceEntity::getOffenceId).collect(Collectors.toSet());

        final List<ResolvedResult> toSend = new ArrayList<>();
        final List<SentItem> sentItems = new ArrayList<>();
        int adjourned = 0;
        for (final Map.Entry<UUID, List<ResolvedResult>> offence : byOffence.entrySet()) {
            if (sentOffences.contains(offence.getKey())) {
                continue;
            }
            if (offence.getValue().stream().anyMatch(r -> isAdjournment(r) && orderedOn(r, hearingDay))) {
                adjourned++;
                continue;
            }
            final List<ResolvedResult> sendable = offence.getValue().stream().filter(r -> !isAdjournment(r)).toList();
            toSend.addAll(sendable);
            if (sendable.stream().anyMatch(r -> r.gobCode() != null)) {
                sentItems.add(SentItem.offence(offence.getKey()));
            }
        }

        final List<UUID> nonOffenceIds = nonOffence.stream().map(ResolvedResult::judicialResultId).filter(Objects::nonNull).toList();
        final Set<UUID> sentResults = nonOffenceIds.isEmpty() ? Set.of()
                : sentOffenceRepository.findByCaseIdAndJudicialResultIdIn(caseId, nonOffenceIds).stream()
                .map(GobSentOffenceEntity::getJudicialResultId).collect(Collectors.toSet());
        int resultsAlreadySent = 0;
        for (final ResolvedResult result : nonOffence) {
            if (result.judicialResultId() != null && sentResults.contains(result.judicialResultId())) {
                resultsAlreadySent++;
            } else if (!isAdjournment(result)) {
                toSend.add(result);
                // a result without a judicialResultId cannot be tracked, so it is not recorded
                if (result.gobCode() != null && result.judicialResultId() != null) {
                    sentItems.add(SentItem.judicialResult(result.judicialResultId()));
                }
            }
        }
        return new OffenceFilterResult(ResolvedCodes.fromResults(toSend, codes.droppedCodes()), List.copyOf(sentItems),
                sentOffences.size(), adjourned, resultsAlreadySent);
    }

    private boolean isAdjournment(final ResolvedResult result) {
        return result.adjournmentMarker()
                || result.cpShortCode() != null && properties.getAdjournmentResultCodes().contains(result.cpShortCode());
    }

    private static boolean orderedOn(final ResolvedResult result, final LocalDate hearingDay) {
        return result.orderedDate() == null || result.orderedDate().equals(hearingDay);
    }
}
