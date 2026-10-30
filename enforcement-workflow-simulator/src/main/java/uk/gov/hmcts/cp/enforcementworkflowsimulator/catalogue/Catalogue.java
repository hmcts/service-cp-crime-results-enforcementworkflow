package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The loaded, validated CIMD-4372 mapping tables. Immutable. */
public record Catalogue(
        Map<String, List<String>> entityNames,
        Map<String, FieldPath> fieldPaths,
        Map<String, ResultCodeEntry> resultCodes) {

    /** NowsDataItems properties for a requested NowsDataItemName. */
    public List<String> propertiesFor(final String nowsDataItemName) {
        final List<String> properties = entityNames.get(nowsDataItemName);
        if (properties == null) {
            throw new IllegalArgumentException("Unknown NowsDataItemName: " + nowsDataItemName);
        }
        return properties;
    }

    /**
     * Whether {@code nowsDataItemName} is one of the contract's 12 permitted values. The single
     * source of truth for that enumeration is {@code entityNames}' key set, loaded from
     * entity-names.yaml — never hand-copied into a second place.
     */
    public boolean isKnownNowsDataItemName(final String nowsDataItemName) {
        return entityNames.containsKey(nowsDataItemName);
    }

    /** Required field labels for a result code, following any alias. */
    public List<String> fieldsFor(final String resultCode) {
        return resolve(resultCode, 0).fields();
    }

    /**
     * Field labels a POSTED result code contributes. Since CIMD-4530 (2026-10-22) CP sends every
     * result code, not just the ones GOB curated, so a contract-valid code with no catalogue row is
     * legitimate input: it contributes no extra NOWS data fields rather than failing. Use {@link
     * #fieldsFor} where an unknown code is a catalogue defect (e.g. an alias target).
     */
    public List<String> fieldsForPostedCode(final String resultCode) {
        return isKnown(resultCode) ? fieldsFor(resultCode) : List.of();
    }

    public FieldPath fieldPath(final String label) {
        return fieldPaths.get(label);
    }

    public boolean isKnown(final String resultCode) {
        return resultCodes.containsKey(resultCode);
    }

    public boolean isPostable(final String resultCode) {
        return resultCodes.containsKey(resultCode) && resultCodes.get(resultCode).postable();
    }

    public Set<String> allCodes() {
        return resultCodes.keySet();
    }

    public Set<String> allProperties() {
        return Set.copyOf(entityNames.values().stream().flatMap(List::stream).toList());
    }

    private ResultCodeEntry resolve(final String resultCode, final int depth) {
        if (depth > entityNames.size() + resultCodes.size()) {
            throw new IllegalStateException("Alias cycle detected resolving result code: " + resultCode);
        }
        final ResultCodeEntry entry = resultCodes.get(resultCode);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown resultCode: " + resultCode);
        }
        return entry.alias() == null ? entry : resolve(entry.alias(), depth + 1);
    }
}
