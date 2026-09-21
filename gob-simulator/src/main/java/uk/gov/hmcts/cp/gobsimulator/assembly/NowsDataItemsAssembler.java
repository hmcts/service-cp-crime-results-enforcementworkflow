package uk.gov.hmcts.cp.gobsimulator.assembly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.gobsimulator.api.model.NowsDataItems;
import uk.gov.hmcts.cp.gobsimulator.catalogue.Catalogue;
import uk.gov.hmcts.cp.gobsimulator.seed.SeedStore;
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
    private final SeedStore seedStore;
    private final ObjectMapper objectMapper;

    public NowsDataItemsAssembler(final Catalogue catalogue,
                                  final ValueResolver valueResolver,
                                  final SeedStore seedStore,
                                  final ObjectMapper objectMapper) {
        this.catalogue = catalogue;
        this.valueResolver = valueResolver;
        this.seedStore = seedStore;
        this.objectMapper = objectMapper;
    }

    /** Assembles with no request-derived overrides — see the four-argument overload. */
    public NowsDataItems assemble(final String caseUrn,
                                  final List<String> resultCodes,
                                  final List<String> requestedNames) {
        return assemble(caseUrn, resultCodes, requestedNames, Map.of());
    }

    /**
     * @param requestOverrides overlay derived from the posted request (see
     *     {@link DefendantDetailsOverlay}), keyed by NowsDataItems root property. Applied last and
     *     wins outright: what the court posted beats what the simulator holds.
     */
    public NowsDataItems assemble(final String caseUrn,
                                  final List<String> resultCodes,
                                  final List<String> requestedNames,
                                  final Map<String, Object> requestOverrides) {

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

        // Task 8, ruling 1 — an entity a posted code only PARTIALLY populated (some but not all
        // of its schema-required fields) is still present after the line above, so the
        // computeIfAbsent never ran defaultFor() for it. Fill just the gaps a baseline-flagged
        // row covers, never overwriting what the code itself contributed.
        requestedRoots.forEach(root -> mergeBaselineGaps(tree, caseUrn, root));

        // The three passes above can only reach a path that field-paths.yaml declares a row for,
        // so a seed could previously override a value but never contribute a field (the bundled
        // seed's defAddress was silently dropped for exactly this reason), and could never supply
        // a SECOND array element, because every catalogue row is pinned to index [0]. These two
        // final passes fix both, in strict precedence order: seed over catalogue default, posted
        // request over everything.
        mergeSeed(tree, caseUrn, requestedRoots);
        mergeOverrides(tree, requestedRoots, requestOverrides);

        return objectMapper.convertValue(tree, NowsDataItems.class);
    }

    /**
     * Deep-merges the seed's own subtree for each requested root, filling GAPS ONLY.
     *
     * <p>Restricted to REQUESTED roots: a seed may legitimately hold more entities than this
     * request asked for, and emitting one the caller did not request would break AC2 as surely as
     * omitting one does.
     *
     * <p>Fill-the-gaps rather than overwrite, because overwriting would buy nothing and cost AC4.
     * For any path a catalogue row covers, {@link ValueResolver} has ALREADY read this same seed
     * and written the seeded value in the first pass — so the only thing an overwrite would change
     * is the two-decimal money scale {@code ValueResolver} coerces to, replacing it with whatever
     * scale the seed file happened to be authored at. AC4 requires amounts at two decimal places,
     * so the coerced form must win. What this pass exists for is the paths the catalogue cannot
     * reach at all.
     */
    private void mergeSeed(final Map<String, Object> tree, final String caseUrn, final Set<String> requestedRoots) {
        seedStore.seedFor(caseUrn).ifPresent(seed -> requestedRoots.stream()
                .filter(seed::hasNonNull)
                .forEach(root -> tree.put(root, deepMerge(tree.get(root), toJava(seed.get(root)), false))));
    }

    /**
     * Applies the request-derived overlay, also restricted to requested roots, overwriting what is
     * already there — what the court just posted outranks both the seed and the catalogue default.
     */
    private void mergeOverrides(final Map<String, Object> tree,
                                final Set<String> requestedRoots,
                                final Map<String, Object> requestOverrides) {
        requestOverrides.entrySet().stream()
                .filter(entry -> requestedRoots.contains(entry.getKey()))
                .forEach(entry -> tree.put(entry.getKey(),
                        deepMerge(tree.get(entry.getKey()), entry.getValue(), true)));
    }

    /**
     * Merges {@code incoming} onto {@code existing}, recursing through maps and lists.
     *
     * <p>{@code overwrite} decides only what happens at a LEAF: {@code true} lets {@code incoming}
     * replace a value already present ({@link #mergeOverrides}), {@code false} keeps what is there
     * and contributes only where nothing is ({@link #mergeSeed}) — the same put/putIfAbsent split
     * {@link #write} already draws for catalogue-driven writes.
     *
     * <p>Maps merge key-wise, so a key only {@code existing} carries survives either way. Lists
     * merge ELEMENT-wise and extend, which is the whole point: a seed supplying three impositions
     * must add elements [1] and [2] alongside the [0] the catalogue built, not replace the list
     * wholesale and lose what a posted result code contributed to [0].
     */
    @SuppressWarnings("unchecked")
    private Object deepMerge(final Object existing, final Object incoming, final boolean overwrite) {
        final Object merged;
        if (existing instanceof Map && incoming instanceof Map) {
            final Map<String, Object> target = (Map<String, Object>) existing;
            ((Map<String, Object>) incoming)
                    .forEach((key, value) -> target.put(key, deepMerge(target.get(key), value, overwrite)));
            merged = target;
        } else if (existing instanceof List && incoming instanceof List) {
            final List<Object> target = (List<Object>) existing;
            final List<Object> source = (List<Object>) incoming;
            for (int i = 0; i < source.size(); i++) {
                if (i < target.size()) {
                    target.set(i, deepMerge(target.get(i), source.get(i), overwrite));
                } else {
                    target.add(source.get(i));
                }
            }
            merged = target;
        } else {
            merged = overwrite || existing == null ? incoming : existing;
        }
        return merged;
    }

    /**
     * Converts a seed node to the mutable Map/List tree the merge works on. Numbers become {@link
     * java.math.BigDecimal} via {@code decimalValue()} — the same choice {@link ValueResolver}
     * makes — so a money value keeps exactly the scale the seed file authored rather than picking
     * up binary floating-point noise on the way through.
     */
    private Object toJava(final JsonNode node) {
        final Object value;
        if (node.isObject()) {
            final Map<String, Object> branch = newBranch();
            node.fieldNames().forEachRemaining(name -> branch.put(name, toJava(node.get(name))));
            value = branch;
        } else if (node.isArray()) {
            final List<Object> list = newList();
            node.forEach(child -> list.add(toJava(child)));
            value = list;
        } else if (node.isNumber()) {
            value = node.decimalValue();
        } else if (node.isBoolean()) {
            value = node.booleanValue();
        } else if (node.isNull()) {
            value = null;
        } else {
            value = node.asText();
        }
        return value;
    }

    /**
     * Minimal content for a requested entity no posted code contributed to.
     *
     * <p>Finding I4: for an <strong>object-typed</strong> root (its field-paths.yaml rows nest
     * further under it, e.g. {@code offences.accountTotal}), this must union only the {@code
     * baseline: true} rows — exactly the set {@link #mergeBaselineGaps} would add on top of a
     * posted code's own (possibly empty) contribution. Unioning every row here, as before, made
     * the "nobody touched it" case richer than the "some code touched it a little" case: posting
     * a {@code fields: []} gap code alone (e.g. {@code ACNOTE}) got the full, rich union (5
     * fields for {@code terms}), while posting it alongside a code that supplies just one field
     * to the same entity (e.g. {@code AEOC}, which supplies {@code terms.english_due}) collapsed
     * back to that one field, because the entity was then non-absent and this method never ran at
     * all — adding a result code appeared to REMOVE fields. Restricting this method to baseline
     * rows for object-typed roots makes the "untouched" floor equal to the "partially touched"
     * floor, so entity content only ever grows as more codes are posted (see {@code
     * NowsDataItemsAssemblerTest#posting_an_additional_fields_empty_code_never_shrinks_an_entity}).
     *
     * <p>A <strong>scalar</strong> root (a row's path IS the root property itself, e.g. {@code
     * accountBalance}) keeps the full-value write regardless of {@code baseline}: it has exactly
     * one candidate row, and AC2 ("a requested entity is never missing") requires that single
     * value to actually be written, whether or not the schema happens to mark it as required —
     * there is no lesser "baseline-only" floor available for it to fall back to.
     */
    private Object defaultFor(final String caseUrn, final String rootProperty) {
        final boolean objectTyped = isObjectTyped(rootProperty);
        final Map<String, Object> branch = new LinkedHashMap<>();
        catalogue.fieldPaths().values().stream()
                .filter(fieldPath -> !fieldPath.unmapped())
                .filter(fieldPath -> rootProperty.equals(fieldPath.rootProperty()))
                .filter(fieldPath -> !objectTyped || fieldPath.baseline())
                .forEach(fieldPath -> put(branch, fieldPath.path(),
                        valueResolver.resolve(caseUrn, fieldPath)));
        final Object value = branch.get(rootProperty);
        return value == null ? newBranch() : value;
    }

    /**
     * Whether {@code rootProperty} is an object-typed NowsDataItems property rather than a
     * scalar leaf. Derived structurally from field-paths.yaml rather than hand-listed: a scalar
     * root has exactly one kind of row, whose {@code path} equals the root property itself (e.g.
     * {@code "Total Balance": { path: accountBalance, ... }}); an object-typed root's rows always
     * nest further under it (e.g. {@code offences.accountTotal}).
     */
    private boolean isObjectTyped(final String rootProperty) {
        return catalogue.fieldPaths().values().stream()
                .filter(fieldPath -> !fieldPath.unmapped())
                .filter(fieldPath -> rootProperty.equals(fieldPath.rootProperty()))
                .anyMatch(fieldPath -> !fieldPath.path().equals(rootProperty));
    }

    /**
     * Fills schema-required gaps left by a posted code's own, partial contribution to
     * {@code rootProperty} (Task 8, ruling 1) — deliberately data-driven rather than the
     * assembler re-deriving "required" from the OpenAPI contract: only rows the catalogue marks
     * {@code baseline: true} are considered, and each is written with {@link #putIfAbsent} so a
     * value the posted code already supplied is never overwritten. Non-required fields have no
     * baseline row, so they are never filled this way — a code's own contribution stays fully
     * observable, which is exactly what a negative-control test needs to be falsifiable.
     *
     * <p>No-op when {@code rootProperty} is missing from {@code tree} entirely: that case was
     * already populated by {@link #defaultFor} above — with the baseline-only floor for an
     * object-typed root, or the single value for a scalar root — so there is nothing left for
     * this method to add.
     */
    private void mergeBaselineGaps(final Map<String, Object> tree, final String caseUrn, final String rootProperty) {
        if (tree.get(rootProperty) != null) {
            catalogue.fieldPaths().values().stream()
                    .filter(fieldPath -> !fieldPath.unmapped() && fieldPath.baseline())
                    .filter(fieldPath -> rootProperty.equals(fieldPath.rootProperty()))
                    .forEach(fieldPath -> putIfAbsent(tree, fieldPath.path(),
                            valueResolver.resolve(caseUrn, fieldPath)));
        }
    }

    /**
     * Writes {@code value} into {@code tree} at a dotted path with optional {@code [n]} indexes,
     * unconditionally overwriting whatever is already there. See {@link #write} for the shared
     * traversal both this and {@link #putIfAbsent} delegate to.
     */
    private void put(final Map<String, Object> tree, final String path, final Object value) {
        write(tree, path, value, true);
    }

    /**
     * Writes {@code value} into {@code tree} at a dotted path only where nothing is there yet —
     * used by {@link #mergeBaselineGaps} so a posted code's own contribution is never overwritten
     * by a baseline default (Task 8, ruling 1). See {@link #write} for the shared traversal.
     */
    private void putIfAbsent(final Map<String, Object> tree, final String path, final Object value) {
        write(tree, path, value, false);
    }

    /**
     * Walks {@code path} one segment at a time, creating intermediate maps (or list slots, for an
     * indexed segment) as needed, and writes {@code value} at the final segment — unconditionally
     * when {@code overwrite} is {@code true} ({@link #put}), or only if that leaf is currently
     * absent when {@code false} ({@link #putIfAbsent}). The loop tracks its position with a
     * {@code cursor} that is reseated to the next container on every non-final segment; PMD's
     * OnlyOneReturn is satisfied by falling out of the loop naturally (there is no early
     * {@code return} to remove) rather than by any change to this walk. The three {@code new}
     * call sites that PMD's AvoidInstantiatingObjectsInLoops flagged (the list and the two branch
     * maps) are factored out to {@link #newList()} and {@link #newBranch()} — each call still
     * allocates a fresh, independent instance every time it runs, so this is a pure extraction
     * with no change to how many objects are created or when.
     */
    @SuppressWarnings("unchecked")
    private void write(final Map<String, Object> tree, final String path, final Object value, final boolean overwrite) {
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
                    // KNOWN LATENT TRAP (see gob-simulator/README.md, "Known gaps"): the padding
                    // loop above always fills a newly-extended slot with a non-null newBranch(),
                    // so list.get(index) == null is never true for a slot this call just padded.
                    // For putIfAbsent (overwrite == false) on a path whose FINAL segment is
                    // indexed, that means the guard below treats the padding placeholder as an
                    // existing value and skips writing — silently dropping the value instead of
                    // protecting an existing one. No current catalogue path has an indexed final
                    // segment, so this has not fired, but it would misfire silently if one is
                    // added with baseline: true.
                    if (overwrite || list.get(index) == null) {
                        list.set(index, value);
                    }
                } else {
                    cursor = list.get(index);
                }
            } else if (last) {
                if (overwrite) {
                    parent.put(name, value);
                } else {
                    parent.putIfAbsent(name, value);
                }
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
