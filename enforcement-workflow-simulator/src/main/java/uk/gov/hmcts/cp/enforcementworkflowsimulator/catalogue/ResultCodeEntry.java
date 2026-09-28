package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.util.List;

/**
 * One row of result-codes.yaml. Exactly one of {@code fields} or {@code alias} is meaningful:
 * an alias row defers entirely to the code it names.
 */
public record ResultCodeEntry(
        String code,
        List<String> fields,
        String alias,
        boolean postable,
        String note) {
}
