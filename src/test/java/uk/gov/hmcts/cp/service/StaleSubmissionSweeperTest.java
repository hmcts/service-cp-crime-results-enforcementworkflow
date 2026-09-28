package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.config.HearingResultProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class StaleSubmissionSweeperTest {

    private static final Instant NOW = Instant.parse("2026-05-03T15:00:00Z");

    private final SubmissionStore store = mock(SubmissionStore.class);
    private final StaleSubmissionSweeper sweeper = new StaleSubmissionSweeper(store, new HearingResultProperties(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void stale_rows_older_than_the_threshold_should_be_marked_interrupted() {
        final UUID stale = UUID.randomUUID();
        when(store.findStaleSending(NOW.minusSeconds(300))).thenReturn(List.of(stale)); // default threshold 5 min

        sweeper.sweep();

        verify(store).markInterrupted(stale);
    }

    @Test
    void no_stale_rows_should_mark_nothing() {
        when(store.findStaleSending(NOW.minusSeconds(300))).thenReturn(List.of());

        sweeper.sweep();

        verify(store, never()).markInterrupted(any());
    }
}
