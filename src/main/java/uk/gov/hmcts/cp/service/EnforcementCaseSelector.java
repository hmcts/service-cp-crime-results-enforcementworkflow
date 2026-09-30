package uk.gov.hmcts.cp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.event.HearingResultedEvent;
import uk.gov.hmcts.cp.event.HearingResultedEvent.CourtApplication;
import uk.gov.hmcts.cp.event.HearingResultedEvent.ProsecutionCase;

import java.util.List;
import java.util.Objects;

/**
 * Chooses the enforcement case and defendant a hearing-resulted event is submitted for, or says why
 * nothing is submitted. The checks run in this order:
 * <ol>
 *   <li>a reshare is never sent to GOB (R2);</li>
 *   <li>Enforcement means the case's OU code equals {@code cp.enforcement.authority-code} (default
 *       GAPGD00), the same predicate as the enforcement gateway (R1);</li>
 *   <li>shapes the BA confirmed are never sent are ignored as a safeguard (R3): more than one
 *       enforcement case, more than one defendant, or an application linked to the case.</li>
 * </ol>
 * Unexpected shapes are logged at WARN, and the rest at INFO or DEBUG, with the hearing id and caseUrn only.
 */
@Slf4j
@Component
public class EnforcementCaseSelector {

    /** In scope for this phase: exactly one enforcement case with exactly one defendant (spec FR-004). */
    private static final int IN_SCOPE_COUNT = 1;

    private final String enforcementAuthorityCode;

    public EnforcementCaseSelector(@Value("${cp.enforcement.authority-code}") final String enforcementAuthorityCode) {
        this.enforcementAuthorityCode = enforcementAuthorityCode;
    }

    public SelectionResult select(final HearingResultedEvent event) {
        final List<ProsecutionCase> enforcementCases = event.hearing() == null ? List.of()
                : safe(event.hearing().prosecutionCases()).stream()
                .filter(this::isEnforcement)
                .toList();
        final SelectionResult result;
        if (event.isReshare() == null) {
            result = skipped(SkipReason.RESHARE_FLAG_MISSING);
        } else if (event.isReshare()) {
            result = skipped(SkipReason.RESHARE);
        } else if (enforcementCases.isEmpty()) {
            result = skipped(SkipReason.NO_ENFORCEMENT_CASE);
        } else if (enforcementCases.size() > IN_SCOPE_COUNT) {
            result = unexpected(event, enforcementCases.getFirst(), SkipReason.MULTIPLE_ENFORCEMENT_CASES);
        } else {
            result = selectDefendant(event, enforcementCases.getFirst());
        }
        return result;
    }

    private SelectionResult selectDefendant(final HearingResultedEvent event, final ProsecutionCase prosecutionCase) {
        final SelectionResult result;
        final int defendants = safe(prosecutionCase.defendants()).size();
        if (defendants == IN_SCOPE_COUNT) {
            result = hasLinkedApplication(event, prosecutionCase)
                    ? unexpected(event, prosecutionCase, SkipReason.LINKED_APPLICATION)
                    : new SelectionResult.Selected(prosecutionCase, prosecutionCase.defendants().getFirst());
        } else {
            result = unexpected(event, prosecutionCase, defendants == 0 ? SkipReason.NO_DEFENDANT : SkipReason.MULTIPLE_DEFENDANTS);
        }
        return result;
    }

    private boolean isEnforcement(final ProsecutionCase prosecutionCase) {
        return prosecutionCase.prosecutionCaseIdentifier() != null
                && enforcementAuthorityCode.equals(prosecutionCase.prosecutionCaseIdentifier().prosecutionAuthorityOUCode());
    }

    private static boolean hasLinkedApplication(final HearingResultedEvent event, final ProsecutionCase prosecutionCase) {
        return safe(event.hearing().courtApplications()).stream()
                .map(CourtApplication::courtApplicationCases)
                .flatMap(cases -> safe(cases).stream())
                .anyMatch(link -> Objects.equals(link.prosecutionCaseId(), prosecutionCase.id()));
    }

    private static SelectionResult unexpected(final HearingResultedEvent event, final ProsecutionCase prosecutionCase,
                                              final SkipReason reason) {
        log.warn("Hearing {} caseUrn {} ignored: {} (out of scope, not expected for Enforcement)", event.hearing().id(),
                prosecutionCase.prosecutionCaseIdentifier().caseURN(), reason);
        return skipped(reason);
    }

    private static SelectionResult skipped(final SkipReason reason) {
        return new SelectionResult.Skipped(reason);
    }

    private static <T> List<T> safe(final List<T> list) {
        return list != null ? list : List.of();
    }
}
