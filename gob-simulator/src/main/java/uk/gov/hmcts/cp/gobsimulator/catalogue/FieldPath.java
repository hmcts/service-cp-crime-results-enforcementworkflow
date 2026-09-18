package uk.gov.hmcts.cp.gobsimulator.catalogue;

/**
 * One row of field-paths.yaml: a CIMD-4372 field label mapped onto a JSON path within
 * NowsDataItems, or explicitly marked as having no home in the v0.3.0 contract.
 *
 * <p>{@code baseline} marks a row that exists purely to satisfy a schema {@code required} block
 * on its entity — never posted by any result code's own field list (Task 8, ruling 1). {@link
 * uk.gov.hmcts.cp.gobsimulator.assembly.NowsDataItemsAssembler} merges only baseline rows into an
 * entity a posted code partially populated, and only where that code left the field missing, so a
 * code's own contribution is never overwritten and non-required fields stay unfilled — the schema
 * decides completeness, not a second parser re-deriving it from the OpenAPI contract.
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
