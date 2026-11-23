package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.client.EnforcementAreaUnavailableException;
import uk.gov.hmcts.cp.client.ReferenceDataClient;

import java.util.Optional;

/**
 * The accounting division code published with GOB's data (CIMD-4640): that of the defendant's enforcement area in
 * reference data, found by the defendant's postcode, else by the hearing court's LJA, as the NOWS functions find it for
 * financial NOWs. Empty when reference data has an enforcement area for neither; hearing-nows then renders the NOW
 * without one.
 */
@Component
@RequiredArgsConstructor
public class AccountingDivisionCodeResolver {

    private final ReferenceDataClient referenceDataClient;

    /** @throws EnforcementAreaUnavailableException when reference data fails or can't be reached */
    public Optional<Integer> resolve(final EnforcementAreaQuery query) {
        Optional<Integer> code = isBlank(query.postcode()) ? Optional.empty()
                : referenceDataClient.findAccountDivisionCodeByPostcode(query.postcode().trim());
        if (code.isEmpty() && !isBlank(query.ljaCode())) {
            code = referenceDataClient.findAccountDivisionCodeByLjaCode(query.ljaCode().trim());
        }
        return code;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
