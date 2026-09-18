package uk.gov.hmcts.cp.gobsimulator.assembly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.gobsimulator.api.model.NowsDataItems;
import uk.gov.hmcts.cp.gobsimulator.catalogue.Catalogue;
import uk.gov.hmcts.cp.gobsimulator.seed.ValueResolver;

/**
 * Builds the NOWS response for one hearing result (spec §7).
 *
 * <p>Works on a nested {@code Map} tree and converts it to {@link NowsDataItems} at the end, so a
 * path the contract does not declare fails conversion rather than reaching CP.
 */
@Component
public class NowsDataItemsAssembler {

    private final Catalogue catalogue;
    private final ValueResolver valueResolver;
    private final ObjectMapper objectMapper;

    public NowsDataItemsAssembler(final Catalogue catalogue,
                                  final ValueResolver valueResolver,
                                  final ObjectMapper objectMapper) {
        this.catalogue = catalogue;
        this.valueResolver = valueResolver;
        this.objectMapper = objectMapper;
    }

    public NowsDataItems assemble(final String caseUrn,
                                  final List<String> resultCodes,
                                  final List<String> requestedNames) {

        final Set<String> requestedRoots = new LinkedHashSet<>();
        requestedNames.forEach(name -> requestedRoots.addAll(catalogue.propertiesFor(name)));

        final Set<String> requiredLabels = new LinkedHashSet<>();
        resultCodes.forEach(code -> requiredLabels.addAll(catalogue.fieldsFor(code)));

        final Map<String, Object> tree = new LinkedHashMap<>();
        requiredLabels.stream()
                .map(catalogue::fieldPath)
                .filter(fieldPath -> !fieldPath.unmapped() && requestedRoots.contains(fieldPath.rootProperty()))
                .forEach(fieldPath -> put(tree, fieldPath.path(), valueResolver.resolve(caseUrn, fieldPath)));

        // AC2 — a requested entity is never missing, even when no posted code feeds it.
        requestedRoots.forEach(root -> tree.computeIfAbsent(root, key -> defaultFor(caseUrn, key)));

        return objectMapper.convertValue(tree, NowsDataItems.class);
    }

    /**
     * Minimal content for a requested entity no posted code contributed to: the catalogue
     * defaults that write into it, unioned into one branch, so schema-required fields are
     * satisfied.
     */
    private Object defaultFor(final String caseUrn, final String rootProperty) {
        final Map<String, Object> branch = new LinkedHashMap<>();
        catalogue.fieldPaths().values().stream()
                .filter(fieldPath -> !fieldPath.unmapped())
                .filter(fieldPath -> rootProperty.equals(fieldPath.rootProperty()))
                .forEach(fieldPath -> put(branch, fieldPath.path(),
                        valueResolver.resolve(caseUrn, fieldPath)));
        final Object value = branch.get(rootProperty);
        return value == null ? newBranch() : value;
    }

    /**
     * Writes {@code value} into {@code tree} at a dotted path with optional {@code [n]} indexes.
     *
     * <p>Walks the path one segment at a time, creating intermediate maps (or list slots, for an
     * indexed segment) as needed, and writes {@code value} at the final segment. The loop tracks
     * its position with a {@code cursor} that is reseated to the next container on every
     * non-final segment; PMD's OnlyOneReturn is satisfied by falling out of the loop naturally
     * (there is no early {@code return} to remove) rather than by any change to this walk. The
     * three {@code new} call sites that PMD's AvoidInstantiatingObjectsInLoops flagged (the list
     * and the two branch maps) are factored out to {@link #newList()} and {@link #newBranch()} —
     * each call still allocates a fresh, independent instance every time it runs, so this is a
     * pure extraction with no change to how many objects are created or when.
     */
    @SuppressWarnings("unchecked")
    private void put(final Map<String, Object> tree, final String path, final Object value) {
        final List<String> segments = List.of(path.split("\\."));
        Object cursor = tree;

        for (int i = 0; i < segments.size(); i++) {
            final String segment = segments.get(i);
            final boolean indexed = segment.endsWith("]");
            final String name = indexed ? segment.substring(0, segment.indexOf('[')) : segment;
            final int index = indexed
                    ? Integer.parseInt(segment.substring(segment.indexOf('[') + 1, segment.length() - 1))
                    : -1;
            final boolean last = i == segments.size() - 1;

            final Map<String, Object> parent = (Map<String, Object>) cursor;
            if (indexed) {
                final List<Object> list = (List<Object>) parent.computeIfAbsent(name, key -> newList());
                while (list.size() <= index) {
                    list.add(newBranch());
                }
                if (last) {
                    list.set(index, value);
                } else {
                    cursor = list.get(index);
                }
            } else if (last) {
                parent.put(name, value);
            } else {
                cursor = parent.computeIfAbsent(name, key -> newBranch());
            }
        }
    }

    private static List<Object> newList() {
        return new ArrayList<>();
    }

    private static Map<String, Object> newBranch() {
        return new LinkedHashMap<>();
    }
}
