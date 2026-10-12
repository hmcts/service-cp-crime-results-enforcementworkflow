package uk.gov.hmcts.cp.service;

import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The defendant's result codes. {@code cpShortCodes} are CP's shortCodes as looked up (distinct, in
 * order), which key the NOWS data-item mapping. {@code gobCodes} are the codes sent to GOB, after the
 * rename and the enum filter. {@code droppedCodes} are the codes left out, for logging. {@code results}
 * keeps each result with its offence, so offences already sent or adjourned can be left out (feature 002).
 */
public record ResolvedCodes(List<String> cpShortCodes, Set<ResultCodeEnum> gobCodes, List<String> droppedCodes,
                            List<ResolvedResult> results) {

    /** Codes without per-result detail (used where only the codes matter). */
    public ResolvedCodes(final List<String> cpShortCodes, final Set<ResultCodeEnum> gobCodes, final List<String> droppedCodes) {
        this(cpShortCodes, gobCodes, droppedCodes, List.of());
    }

    /** The codes of the given results; {@code droppedCodes} as reported for the whole share. */
    public static ResolvedCodes fromResults(final List<ResolvedResult> results, final List<String> droppedCodes) {
        final Set<String> cpShortCodes = new LinkedHashSet<>();
        final Set<ResultCodeEnum> gobCodes = new LinkedHashSet<>();
        for (final ResolvedResult result : results) {
            if (result.cpShortCode() != null) {
                cpShortCodes.add(result.cpShortCode());
            }
            if (result.gobCode() != null) {
                gobCodes.add(result.gobCode());
            }
        }
        return new ResolvedCodes(List.copyOf(cpShortCodes), gobCodes, List.copyOf(droppedCodes), List.copyOf(results));
    }

    /** The result definition ids of the results that carry a GOB code. */
    public List<UUID> gobResultDefinitionIds() {
        return results.stream().filter(r -> r.gobCode() != null).map(ResolvedResult::judicialResultTypeId)
                .filter(Objects::nonNull).distinct().toList();
    }
}
