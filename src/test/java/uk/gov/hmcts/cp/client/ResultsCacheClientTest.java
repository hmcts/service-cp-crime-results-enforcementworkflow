package uk.gov.hmcts.cp.client;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Feature 003 (contracts/results-data-sources.md §1): the Results cache, read by hearing and day; a failure is a miss. */
class ResultsCacheClientTest {

    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");
    private static final LocalDate DAY = LocalDate.parse("2026-05-03");
    private static final String KEY = "INT_" + HEARING_ID + "_" + DAY + "_result_"; // the Results cache key format

    private final RedisClient redis = mock(RedisClient.class);
    @SuppressWarnings("unchecked")
    private final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> commands = mock(RedisCommands.class);
    private final ResultsCacheClient client = new ResultsCacheClient(redis);

    @BeforeEach
    void connects() {
        when(redis.connect()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        when(connection.sync()).thenReturn(commands);
    }

    @Test
    void key_should_follow_the_results_context_format() {
        assertThat(ResultsCacheClient.keyOf(HEARING_ID, DAY)).isEqualTo(KEY);
    }

    @Test
    void cached_value_should_be_returned() {
        when(commands.get(KEY)).thenReturn("{\"hearing\":{}}");

        assertThat(client.sharedResults(HEARING_ID, DAY)).contains("{\"hearing\":{}}");
    }

    @Test
    void absent_or_blank_value_should_be_a_miss() {
        when(commands.get(KEY)).thenReturn(null, "  ");

        assertThat(client.sharedResults(HEARING_ID, DAY)).isEmpty();
        assertThat(client.sharedResults(HEARING_ID, DAY)).isEmpty();
    }

    @Test
    void command_failure_should_be_a_miss() {
        when(commands.get(KEY)).thenThrow(new RedisCommandTimeoutException("timeout"));

        assertThat(client.sharedResults(HEARING_ID, DAY)).isEmpty();
    }

    @Test
    void cache_that_cannot_be_reached_should_be_a_miss_and_be_retried_on_the_next_read() {
        when(redis.connect()).thenThrow(new RedisConnectionException("refused")).thenReturn(connection);
        when(commands.get(KEY)).thenReturn("{\"hearing\":{}}");

        assertThat(client.sharedResults(HEARING_ID, DAY)).isEmpty();
        assertThat(client.sharedResults(HEARING_ID, DAY)).contains("{\"hearing\":{}}");
    }

    @Test
    void connection_should_be_opened_on_the_first_read_and_reused() {
        when(commands.get(KEY)).thenReturn("{}");

        client.sharedResults(HEARING_ID, DAY);
        client.sharedResults(HEARING_ID, DAY);

        verify(redis, times(1)).connect();
    }

    @Test
    void connection_closed_for_good_should_be_reopened() {
        when(connection.isOpen()).thenReturn(false);
        when(commands.get(KEY)).thenReturn("{}");

        client.sharedResults(HEARING_ID, DAY);
        client.sharedResults(HEARING_ID, DAY);

        verify(redis, times(2)).connect();
    }

    @Test
    void close_should_close_the_open_connection() {
        when(commands.get(KEY)).thenReturn("{}");
        client.sharedResults(HEARING_ID, DAY);

        client.close();

        verify(connection).close();
    }
}
