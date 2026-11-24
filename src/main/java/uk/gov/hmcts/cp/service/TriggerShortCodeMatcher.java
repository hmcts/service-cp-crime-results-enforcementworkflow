package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.ResultNotificationProperties;
import uk.gov.hmcts.cp.entity.Area;

import java.util.List;

/**
 * The FR-011 gate (feature 005): does the hearing's <b>current</b> (post-amendment) state include at least one
 * shortCode from the trigger list for the case's area. Identical logic for FIRST_SHARE and AMEND_RESHARE
 * (Clarification 2026-11-24): re-evaluated fresh against the current state on every reshare, never against whether
 * the original first share qualified.
 */
@Component
@RequiredArgsConstructor
public class TriggerShortCodeMatcher {

    private final ResultNotificationProperties properties;

    public boolean matches(final List<ResolvedResult> results, final Area area) {
        return results.stream().anyMatch(result -> properties.isTriggerShortCode(area, result.cpShortCode()));
    }
}
