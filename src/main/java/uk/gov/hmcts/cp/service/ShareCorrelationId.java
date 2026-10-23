package uk.gov.hmcts.cp.service;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The share correlation id (feature 002 research R18): {@code UUID.nameUUIDFromBytes(hearingId + "|" + hearingDay)}.
 * cpp-context-hearing-nows computes it with the same rule and the same test vectors; keep the two in sync.
 * It keys the submission of a share (a repeated delivery of the same hearing day's share has the same id) and the
 * hand-over to hearing-nows.
 *
 * <p>Interim attribute (OQ-9): the hearing day, the only per-share attribute both services hold; hearing-nows reads
 * it from the NOW's order date.
 */
public final class ShareCorrelationId {

    private ShareCorrelationId() {
    }

    public static UUID forShare(final UUID hearingId, final LocalDate hearingDay) {
        final String name = hearingId.toString().toLowerCase(Locale.ROOT) + "|" + hearingDay;
        return UUID.nameUUIDFromBytes(name.getBytes(UTF_8));
    }
}
