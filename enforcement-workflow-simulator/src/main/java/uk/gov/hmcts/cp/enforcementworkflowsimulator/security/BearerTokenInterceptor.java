package uk.gov.hmcts.cp.enforcementworkflowsimulator.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.UnauthorizedException;

/**
 * Requires an {@code Authorization: Bearer <token>} header naming a token {@link TokenStore}
 * issued and has not expired.
 *
 * <p>An interceptor rather than a servlet {@code Filter} on purpose: a filter runs ahead of
 * {@code @RestControllerAdvice} and would have to serialise its own error body, duplicating the
 * {@code ErrorResponse} shape {@code GlobalExceptionHandler} already owns — the exact divergence
 * that advice exists to prevent. An interceptor's exception reaches the advice through the normal
 * resolver chain, so a 401 body is produced by the same code path as every other error.
 */
@Component
public class BearerTokenInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenStore tokenStore;

    public BearerTokenInterceptor(final TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public boolean preHandle(final HttpServletRequest request,
                             final HttpServletResponse response,
                             final Object handler) {
        final String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            throw new UnauthorizedException("no Authorization header");
        }
        if (!header.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("Authorization header does not use the Bearer scheme");
        }
        if (!tokenStore.isValid(header.substring(BEARER_PREFIX.length()).trim())) {
            throw new UnauthorizedException("bearer token was never issued, or has expired");
        }
        return true;
    }
}
