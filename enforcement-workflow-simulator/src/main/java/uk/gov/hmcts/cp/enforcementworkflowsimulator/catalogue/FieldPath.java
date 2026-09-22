package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

/**
 * One row of field-paths.yaml: a CIMD-4372 field label mapped onto a JSON path within
 * NowsDataItems, or explicitly marked as having no home in the v0.3.0 contract.
 *
 * <p>{@code baseline} drives two things (see the "baseline: true" section of the module README
 * for the full rationale): most rows carry it because they satisfy a schema {@code required}
 * block on their entity (Task 8, ruling 1) — {@link
 * uk.gov.hmcts.cp.enforcementworkflowsimulator.assembly.NowsDataItemsAssembler} merges only baseline rows into an
 * entity a posted code partially populated, and only where that code left the field missing, so a
 * code's own contribution is never overwritten and non-required fields stay unfilled. A couple of
 * rows carry it for an unrelated reason: they are the chosen non-empty floor for an object-typed
 * entity that has no schema-required row of its own, so a requested-but-untouched instance of
 * that entity is never emitted as {@code {}} (Finding I4's follow-up gap). Either way, this flag
 * being posted by some result code's own field list, or not, is an independent fact.
 */
public record FieldPath(
        String label,
        String path,
        String type,
        Object defaultValue,
        boolean unmapped,
        String reason,
        boolean baseline) {

    /** The top-level NowsDataItems property this path writes into, e.g. {@code offences}. */
    public String rootProperty() {
        if (unmapped) {
            throw new IllegalStateException("Label '" + label + "' is unmapped and has no root property");
        }
        final int dot = path.indexOf('.');
        final int bracket = path.indexOf('[');
        int end = path.length();
        if (dot >= 0) {
            end = Math.min(end, dot);
        }
        if (bracket >= 0) {
            end = Math.min(end, bracket);
        }
        return path.substring(0, end);
    }
}
