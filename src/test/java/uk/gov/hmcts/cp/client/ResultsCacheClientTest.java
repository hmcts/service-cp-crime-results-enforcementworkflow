package uk.gov.hmcts.cp.client;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Feature 003 (contracts/results-data-sources.md §1): the Results cache, read by hearing and day; a failure is a miss. */
class ResultsCacheClientTest {

    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate DAY = LocalDate.parse("2026-05-03");
    private static final String KEY = "INT_04180ff1-99b0-40b7-9929-ca05bdc767d8_2026-05-03_result_";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ResultsCacheClient client = new ResultsCacheClient(redis);

    @Test
    void key_should_follow_the_results_context_format() {
        assertThat(ResultsCacheClient.keyOf(HEARING_ID, DAY)).isEqualTo(KEY);
    }

    @Test
    void cached_value_should_be_returned() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(KEY)).thenReturn("{\"hearing\":{}}");

        assertThat(client.sharedResults(HEARING_ID, DAY)).contains("{\"hearing\":{}}");
    }

    @Test
    void blank_value_should_be_a_miss() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(KEY)).thenReturn("  ");

        assertThat(client.sharedResults(HEARING_ID, DAY)).isEmpty();
    }

    @Test
    void redis_failure_should_be_a_miss() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(KEY)).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(client.sharedResults(HEARING_ID, DAY)).isEmpty();
    }
}
