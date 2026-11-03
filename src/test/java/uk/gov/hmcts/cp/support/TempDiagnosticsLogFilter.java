package uk.gov.hmcts.cp.support;

import uk.gov.hmcts.cp.diagnostics.TempPayloadLog;

import java.util.stream.Collectors;

/**
 * TEMP DIAGNOSTICS (CIMD-4530, task T090): the captured log without the lines of the temporary payload logger, so the
 * "no personal data in logs" assertions keep applying to every other logger. Remove with {@link TempPayloadLog}.
 */
public final class TempDiagnosticsLogFilter {

    private static final String MARKER = "\"logger_name\":\"" + TempPayloadLog.LOGGER_NAME + "\"";

    private TempDiagnosticsLogFilter() {
    }

    public static String withoutTempDiagnostics(final String log) {
        return log.lines().filter(line -> !line.contains(MARKER)).collect(Collectors.joining("\n"));
    }

    public static String onlyTempDiagnostics(final String log) {
        return log.lines().filter(line -> line.contains(MARKER)).collect(Collectors.joining("\n"));
    }
}
