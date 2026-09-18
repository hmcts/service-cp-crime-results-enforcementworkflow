package uk.gov.hmcts.cp.gobsimulator.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.gobsimulator.catalogue.FieldPath;

/** Resolves the value for one field: seeded if available, otherwise the catalogue default. */
@Component
public class ValueResolver {

    private static final int MONEY_SCALE = 2;

    private final SeedStore seedStore;

    public ValueResolver(final SeedStore seedStore) {
        this.seedStore = seedStore;
    }

    public Object resolve(final String caseUrn, final FieldPath fieldPath) {
        if (fieldPath.unmapped()) {
            throw new IllegalArgumentException(
                    "Cannot resolve a value for unmapped label: " + fieldPath.label());
        }
        return seededValue(caseUrn, fieldPath)
                .orElseGet(() -> coerce(fieldPath.defaultValue(), fieldPath.type()));
    }

    private Optional<Object> seededValue(final String caseUrn, final FieldPath fieldPath) {
        return seedStore.seedFor(caseUrn)
                .map(seed -> seed.at(jsonPointer(fieldPath.path())))
                .filter(node -> !node.isMissingNode() && !node.isNull())
                .map(node -> coerce(toJava(node), fieldPath.type()));
    }

    /** Converts {@code offences.offence[0].timeOfOffence} to {@code /offences/offence/0/timeOfOffence}. */
    private String jsonPointer(final String path) {
        return "/" + path.replace("[", ".").replace("]", "").replace('.', '/');
    }

    private Object toJava(final JsonNode node) {
        final Object value;
        if (node.isNumber()) {
            value = node.decimalValue();
        } else if (node.isBoolean()) {
            value = node.booleanValue();
        } else {
            value = node.asText();
        }
        return value;
    }

    private Object coerce(final Object value, final String type) {
        final Object coerced;
        if (value == null) {
            coerced = null;
        } else {
            coerced = switch (type) {
                case "number" -> new BigDecimal(value.toString()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                case "integer" -> Integer.valueOf(value.toString());
                default -> value.toString();
            };
        }
        return coerced;
    }
}
