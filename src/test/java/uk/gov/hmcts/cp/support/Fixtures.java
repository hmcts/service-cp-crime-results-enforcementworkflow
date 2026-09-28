package uk.gov.hmcts.cp.support;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.event.HearingResultedEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the hearing-resulted event fixtures in src/test/resources/events (tasks.md T016). */
public final class Fixtures {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Fixtures() {
    }

    public static String json(final String resource) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing test resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static HearingResultedEvent event(final String name) {
        return MAPPER.readValue(json("events/" + name), HearingResultedEvent.class);
    }
}
