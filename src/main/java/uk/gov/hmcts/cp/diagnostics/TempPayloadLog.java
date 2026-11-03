package uk.gov.hmcts.cp.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import uk.gov.hmcts.cp.config.PayloadJson;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * TEMP DIAGNOSTICS (CIMD-4530, 2026-10-23): detailed QA logs at the connection points (payloads, URLs, destinations,
 * statuses, exception messages, root causes and stack traces), as the enforcement gateway does for hearing confirmed.
 * They deliberately include personal and GOB account data, against FR-019 / constitution IV, for test environments
 * only. Every line goes through the dedicated logger {@value #LOGGER_NAME} (tests asserting "no personal data in logs"
 * ignore it). Remove this class, every call marked {@code TEMP DIAGNOSTICS} and the test filters before merging to main
 * (task T090).
 */
public final class TempPayloadLog {

    public static final String LOGGER_NAME = "uk.gov.hmcts.cp.TempDiagnostics";
    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);
    private static final String PREFIX = "TEMP DIAGNOSTICS ";
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}\\u0080-\\u009F]");

    private TempPayloadLog() {
    }

    public static void info(final String format, final Object... args) {
        safely(() -> LOG.info(PREFIX + format, sanitizeArgs(args)));
    }

    public static void warn(final String format, final Object... args) {
        safely(() -> LOG.warn(PREFIX + format, sanitizeArgs(args)));
    }

    /** Pass the throwable as the last argument to get its stack trace. */
    public static void error(final String format, final Object... args) {
        safely(() -> LOG.error(PREFIX + format, sanitizeArgs(args)));
    }

    /** Diagnostics never fail the flow they describe (e.g. an argument whose {@code toString()} throws). */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void safely(final Runnable logging) {
        try {
            logging.run();
        } catch (RuntimeException e) {
            LOG.debug("TEMP DIAGNOSTICS line not written: {}", e.getClass().getSimpleName());
        }
    }

    /** Log injection (CodeQL): line breaks are escaped and other control characters replaced; throwables are kept. */
    private static Object[] sanitizeArgs(final Object... args) {
        return args == null ? new Object[0]
                : Arrays.stream(args).map(arg -> arg instanceof Throwable ? arg : sanitizeForLog(arg)).toArray();
    }

    /* default */ static String sanitizeForLog(final Object value) {
        final String escaped = String.valueOf(value).replace("\r", "\\r").replace("\n", "\\n");
        return CONTROL_CHARACTERS.matcher(escaped).replaceAll("?");
    }

    /** The object as JSON for the log (its {@code toString()} when it can't be written). */
    public static String json(final Object value) {
        String json;
        try {
            json = value instanceof String string ? string : PayloadJson.MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            json = String.valueOf(value);
        }
        return json;
    }
}
