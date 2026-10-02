package uk.gov.hmcts.cp.enforcementworkflowsimulator.logging;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArgument;
import net.logstash.logback.argument.StructuredArguments;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs every request to, and response from, the {@code HearingController} endpoints at INFO, with
 * the bodies embedded as nested JSON objects (via logstash's {@code arguments} provider) rather
 * than escaped strings, so {@code jq} can query them directly.
 *
 * <p>This deliberately deviates from the logging standard's "never log full bodies / PII" rule —
 * see ADR-005. The simulator only ever handles synthetic data and {@code LiveEnvironmentGuard}
 * stops it starting in a live environment. The {@code Authorization} header is never logged.
 *
 * <p>A filter rather than logging in the controller, so that 400/401 rejections — produced before
 * the controller runs — are logged too. The request body is read up front so the request line is
 * written before processing starts, keeping the log in chronological order.
 */
@Slf4j
public class HearingTrafficLoggingFilter extends OncePerRequestFilter {

    /** BigDecimal keeps a number's scale, so a logged {@code 1250.00} is not rewritten as {@code 1250.0}. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        final CachedBodyRequest cachedRequest = new CachedBodyRequest(request);
        final ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);

        log.info("Hearing request received", requestArguments(cachedRequest));
        try {
            filterChain.doFilter(cachedRequest, cachedResponse);
        } finally {
            log.info("Hearing response sent", responseArguments(request, cachedResponse));
            cachedResponse.copyBodyToResponse();
        }
    }

    private static Object[] requestArguments(final CachedBodyRequest request) {
        final List<StructuredArgument> arguments = new ArrayList<>();
        arguments.add(StructuredArguments.keyValue("httpMethod", request.getMethod()));
        arguments.add(StructuredArguments.keyValue("path", request.getRequestURI()));
        addCorrelationId(arguments, request);
        addBody(arguments, "requestBody", request.body());
        return arguments.toArray();
    }

    private static Object[] responseArguments(final HttpServletRequest request,
                                              final ContentCachingResponseWrapper response) {
        final List<StructuredArgument> arguments = new ArrayList<>();
        arguments.add(StructuredArguments.keyValue("httpMethod", request.getMethod()));
        arguments.add(StructuredArguments.keyValue("path", request.getRequestURI()));
        arguments.add(StructuredArguments.keyValue("status", response.getStatus()));
        addCorrelationId(arguments, request);
        addBody(arguments, "responseBody", response.getContentAsByteArray());
        return arguments.toArray();
    }

    private static void addCorrelationId(final List<StructuredArgument> arguments, final HttpServletRequest request) {
        final String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId != null) {
            arguments.add(StructuredArguments.keyValue("correlationId", correlationId));
        }
    }

    /**
     * Embeds a JSON body as a nested object, compacted onto one line. A body that is not valid
     * JSON (a malformed Postman request, say) is logged as a plain string instead; an empty body
     * is left out.
     */
    private static void addBody(final List<StructuredArgument> arguments, final String fieldName, final byte[] body) {
        if (body.length == 0) {
            return;
        }
        try {
            arguments.add(StructuredArguments.raw(fieldName, JSON.readTree(body).toString()));
        } catch (final IOException notJson) {
            arguments.add(StructuredArguments.keyValue(fieldName, new String(body, StandardCharsets.UTF_8)));
        }
    }

    /** Reads the whole body once, then serves it from memory to everything downstream. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        /* default */ CachedBodyRequest(final HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        /* default */ byte[] body() {
            return cachedBody.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            final ByteArrayInputStream source = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(final byte[] buffer, final int offset, final int length) {
                    return source.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(final ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous reads only");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
