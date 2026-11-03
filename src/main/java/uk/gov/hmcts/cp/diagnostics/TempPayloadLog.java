package uk.gov.hmcts.cp.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import uk.gov.hmcts.cp.config.PayloadJson;

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

    private static Object[] sanitizeArgs(final Object... args) {
        if (args == null || args.length == 0) {
            return args;
        }
        final Object[] sanitized = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            final Object arg = args[i];
            if (arg instanceof Throwable) {
                sanitized[i] = arg;
            } else {
                sanitized[i] = sanitizeForLog(arg);
            }
        }
        return sanitized;
    }

    private static String sanitizeForLog(final Object value) {
        final String raw = String.valueOf(value);
        final StringBuilder sanitized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '\r') {
                sanitized.append("\\r");
            } else if (c == '\n') {
                sanitized.append("\\n");
            } else if (Character.isISOControl(c)) {
                sanitized.append('?');
            } else {
                sanitized.append(c);
            }
        }
        return sanitized.toString();
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
