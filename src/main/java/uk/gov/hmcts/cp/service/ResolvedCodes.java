package uk.gov.hmcts.cp.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The defendant's result codes. {@code cpShortCodes} are CP's shortCodes as looked up (distinct, in
 * order), which key the NOWS data-item mapping. {@code gobCodes} are the codes sent to GOB, after the
 * rename (every code is sent, feature 003 FR-010). {@code droppedCodes} are the codes left out, for logging. {@code results}
 * keeps each result with its offence, so offences already sent or adjourned can be left out (feature 002).
 */
public record ResolvedCodes(List<String> cpShortCodes, Set<String> gobCodes, List<String> droppedCodes,
                            List<ResolvedResult> results) {

    /** Codes without per-result detail (used where only the codes matter). */
    public ResolvedCodes(final List<String> cpShortCodes, final Set<String> gobCodes, final List<String> droppedCodes) {
        this(cpShortCodes, gobCodes, droppedCodes, List.of());
    }

    /** The codes of the given results; {@code droppedCodes} as reported for the whole share. */
    public static ResolvedCodes fromResults(final List<ResolvedResult> results, final List<String> droppedCodes) {
        final Set<String> cpShortCodes = new LinkedHashSet<>();
        final Set<String> gobCodes = new LinkedHashSet<>();
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

}
