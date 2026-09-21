package uk.gov.hmcts.cp.gobsimulator.security;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies {@link BearerTokenInterceptor} to the two operations the contract declares
 * {@code ClientCredentials}-secured, and nothing else — {@code /auth/token} has to stay reachable
 * for a caller to obtain a token at all.
 *
 * <p>Deliberately not {@code spring-boot-starter-security}: that starter secures every endpoint by
 * default, so adopting it would mean re-opening {@code /auth/token} and switching off CSRF and
 * form login — more configuration than the feature, for a contract that specifies an opaque token
 * rather than a JWT. See ADR-004.
 *
 * <p>The interceptor arrives through an {@link ObjectProvider} because this class also supplies
 * the {@link Clock} that {@link TokenStore} — and therefore the interceptor — depends on.
 * Injecting it directly would close that loop into a constructor cycle; resolving it lazily, once
 * MVC asks for the interceptor list, does not.
 */
@Configuration
public class SecurityConfiguration implements WebMvcConfigurer {

    private static final String[] SECURED_PATHS = {"/hearing", "/hearing/result"};

    private final ObjectProvider<BearerTokenInterceptor> bearerTokenInterceptor;

    public SecurityConfiguration(final ObjectProvider<BearerTokenInterceptor> bearerTokenInterceptor) {
        this.bearerTokenInterceptor = bearerTokenInterceptor;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(bearerTokenInterceptor.getObject())
                .addPathPatterns(SECURED_PATHS);
    }
}
