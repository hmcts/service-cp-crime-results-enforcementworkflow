package uk.gov.hmcts.cp.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON for the GOB hearing-result payloads, both what is sent and what is stored. Absent optional
 * blocks are omitted rather than written as {@code null}, because the Libra schemas are
 * {@code additionalProperties: false} and not nullable. The generated contract models don't mark
 * them NON_NULL themselves. Unknown response properties are tolerated.
 */
public final class PayloadJson {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private PayloadJson() {
    }
}
