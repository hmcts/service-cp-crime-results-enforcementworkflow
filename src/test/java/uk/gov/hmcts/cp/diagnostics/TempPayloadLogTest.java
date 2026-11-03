package uk.gov.hmcts.cp.diagnostics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** TEMP DIAGNOSTICS (research I19): diagnostics never fail the flow they describe. Remove with the class (T090). */
class TempPayloadLogTest {

    private static final Object THROWING = new Object() {
        @Override
        public String toString() {
            throw new IllegalStateException("toString failed");
        }
    };

    @Test
    void logging_an_argument_whose_to_string_throws_should_never_throw() {
        assertThatCode(() -> {
            TempPayloadLog.info("value {}", THROWING);
            TempPayloadLog.warn("value {}", THROWING);
            TempPayloadLog.error("value {}", THROWING, new IllegalArgumentException("cause"));
        }).doesNotThrowAnyException();
    }

    @Test
    void logged_values_should_not_be_able_to_start_a_new_log_line() {
        assertThat(TempPayloadLog.sanitizeForLog("caseUrn\r\nforged line\u0007\u0085end"))
                .isEqualTo("caseUrn\\r\\nforged line??end");
        assertThat(TempPayloadLog.sanitizeForLog(null)).isEqualTo("null");
    }

    @Test
    void json_should_fall_back_to_to_string_when_the_value_cannot_be_written() {
        final Object unwritable = new Object() {
            public Object getSelf() {
                return this;
            }

            @Override
            public String toString() {
                return "fallback";
            }
        };

        assertThat(TempPayloadLog.json(unwritable)).isEqualTo("fallback");
        assertThat(TempPayloadLog.json("as is")).isEqualTo("as is");
    }
}
