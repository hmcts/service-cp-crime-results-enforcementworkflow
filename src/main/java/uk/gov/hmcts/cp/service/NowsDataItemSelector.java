package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.HearingResultProperties;
import uk.gov.hmcts.cp.openapi.model.NowsDataItemName;
import uk.gov.hmcts.cp.openapi.model.NowsDataItemRequest;
import uk.gov.hmcts.cp.openapi.model.NowsDataRequest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the NOWS data items to ask GOB for (research.md R7, gaps #1/#2). The result is the
 * de-duplicated union of the configured items for the resulted CP shortCodes, using GOB's
 * {@code NowsDataItemName} values. When the union is empty, {@code nowsDataRequest} is left out
 * entirely: an empty array would fail {@code minItems: 1}. The mapping comes from configuration
 * ({@code cp.hearing-result.nows-data-items-by-short-code}), so rows can change without a code
 * change (FR-011).
 */
@Component
@RequiredArgsConstructor
public class NowsDataItemSelector {

    private final HearingResultProperties properties;

    public Optional<NowsDataRequest> select(final List<String> cpShortCodes) {
        final Set<NowsDataItemName> names = new LinkedHashSet<>();
        cpShortCodes.forEach(code -> properties.getNowsDataItemsByShortCode().getOrDefault(code, List.of())
                .forEach(name -> names.add(NowsDataItemName.fromValue(name))));
        final Optional<NowsDataRequest> request;
        if (names.isEmpty()) {
            request = Optional.empty();
        } else {
            final NowsDataRequest nowsDataRequest = new NowsDataRequest();
            names.forEach(name -> nowsDataRequest.addNowsDataItemsItem(new NowsDataItemRequest().name(name)));
            request = Optional.of(nowsDataRequest);
        }
        return request;
    }
}
