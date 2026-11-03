package uk.gov.hmcts.cp.diagnostics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * TEMP DIAGNOSTICS (CIMD-4530, see {@link TempPayloadLog}): logs every call to {@code /enforcement-notifications} as
 * received and answered (method, path, CJSCPPUID, raw body, status, response body, duration), whatever the outcome
 * (202, 400, 403, 409, 503). Remove before merging to main (task T090).
 */
@Component
public class TempNotificationTrafficFilter extends OncePerRequestFilter {

    private static final String PATH = "/enforcement-notifications";
    private static final int MAX_BODY = 64 * 1024;

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH);
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {
        final ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request, MAX_BODY);
        final ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        final long start = System.nanoTime();
        try {
            chain.doFilter(cachedRequest, cachedResponse);
        } finally {
            final String responseBody = new String(cachedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
            // the answer goes back first: the diagnostics must never change or lose it
            cachedResponse.copyBodyToResponse();
            log(request, cachedRequest, cachedResponse.getStatus(), (System.nanoTime() - start) / 1_000_000, responseBody);
        }
    }

    private static void log(final HttpServletRequest request, final ContentCachingRequestWrapper cachedRequest, final int status,
                            final long millis, final String responseBody) {
        TempPayloadLog.info("{} {} received: CJSCPPUID={}, contentType={}, body={} -> answered HTTP {} in {} ms, body={}",
                request.getMethod(), request.getRequestURI(), request.getHeader("CJSCPPUID"), request.getContentType(),
                new String(cachedRequest.getContentAsByteArray(), StandardCharsets.UTF_8), status, millis, responseBody);
    }
}
