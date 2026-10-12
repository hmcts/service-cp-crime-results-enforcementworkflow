package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.ReferenceDataClient;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.event.HearingResultedEvent.Defendant;
import uk.gov.hmcts.cp.event.HearingResultedEvent.JudicialResult;
import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Resolves a defendant's judicial results to GOB result codes (research.md R4-R6, R16):
 * <ol>
 *   <li>gather the results at offence, defendant-case and hearing (defendant) level;</li>
 *   <li>look up each result's shortCode in reference data by {@code judicialResultTypeId}, on its
 *       ordered date (falling back to the hearing day);</li>
 *   <li>rename CP codes to GOB codes (TFOUT→TFOOUT, WC→DW, WWDN→WDN);</li>
 *   <li>keep only codes in the GOB {@code resultCode} enum. The others are dropped and reported.</li>
 * </ol>
 * Each result is kept with its offence (feature 002 research R19), so offences already sent or adjourned can be
 * left out before the request is built.
 */
@Component
@RequiredArgsConstructor
public class ResultCodeResolver {

    /* default */ static final String UNKNOWN_DEFINITION_PREFIX = "unknown-result-definition:";

    private final ReferenceDataClient referenceDataClient;
    private final HearingResultProperties properties;

    public ResolvedCodes resolve(final HearingResultedEvent event, final Defendant defendant) {
        final List<ResolvedResult> resolved = new ArrayList<>();
        final Set<String> dropped = new LinkedHashSet<>();
        for (final OffenceResult offenceResult : resultsFor(event, defendant)) {
            final JudicialResult result = offenceResult.result();
            final LocalDate on = result.orderedDate() != null ? result.orderedDate() : event.hearingDay();
            final Optional<String> shortCode = referenceDataClient.findShortCode(result.judicialResultTypeId(), on);
            ResultCodeEnum gobCode = null;
            if (shortCode.isEmpty()) {
                dropped.add(UNKNOWN_DEFINITION_PREFIX + result.judicialResultTypeId());
            } else {
                final String renamed = properties.getResultCodeRenames().getOrDefault(shortCode.get(), shortCode.get());
                gobCode = toGobEnum(renamed).orElse(null);
                if (gobCode == null) {
                    dropped.add(shortCode.get());
                }
            }
            resolved.add(new ResolvedResult(offenceResult.offenceId(), result.judicialResultId(), result.judicialResultTypeId(),
                    shortCode.orElse(null), gobCode, result.hasAdjournmentMarker(), result.orderedDate()));
        }
        return ResolvedCodes.fromResults(resolved, new ArrayList<>(dropped));
    }

    private static boolean sameId(final UUID a, final UUID b) {
        return a != null && a.equals(b);
    }

    private static List<OffenceResult> resultsFor(final HearingResultedEvent event, final Defendant defendant) {
        final Stream<OffenceResult> offenceLevel = safe(defendant.offences()).stream()
                .flatMap(offence -> safe(offence.judicialResults()).stream().map(r -> new OffenceResult(offence.id(), r)));
        final Stream<OffenceResult> defendantCaseLevel = safe(defendant.defendantCaseJudicialResults()).stream()
                .map(r -> new OffenceResult(null, r));
        final Stream<OffenceResult> hearingLevel = safe(event.hearing().defendantJudicialResults()).stream()
                // R16: by masterDefendantId, or by defendantId when that is what CP populated; never on two nulls
                .filter(r -> sameId(r.masterDefendantId(), defendant.masterDefendantId()) || sameId(r.defendantId(), defendant.id()))
                .map(r -> new OffenceResult(null, r.judicialResult()));
        return Stream.of(offenceLevel, defendantCaseLevel, hearingLevel)
                .flatMap(s -> s)
                .filter(r -> r.result() != null && r.result().judicialResultTypeId() != null)
                .toList();
    }

    /** A result with its offence; {@code offenceId} is null for defendant-case and hearing-level results. */
    private record OffenceResult(UUID offenceId, JudicialResult result) {
    }

    private static Optional<ResultCodeEnum> toGobEnum(final String code) {
        Optional<ResultCodeEnum> gobCode;
        try {
            gobCode = Optional.of(ResultCodeEnum.fromValue(code));
        } catch (IllegalArgumentException e) {
            gobCode = Optional.empty();
        }
        return gobCode;
    }

    private static <T> List<T> safe(final List<T> list) {
        return list != null ? list : List.of();
    }
}
