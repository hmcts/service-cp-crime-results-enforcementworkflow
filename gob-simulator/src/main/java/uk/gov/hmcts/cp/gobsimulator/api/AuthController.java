package uk.gov.hmcts.cp.gobsimulator.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import uk.gov.hmcts.cp.gobsimulator.api.model.OAuthTokenResponse;
import uk.gov.hmcts.cp.gobsimulator.security.TokenStore;

/**
 * Issues an opaque bearer token. Credentials are not checked — the contract declares no client
 * registry, and what this simulator needs to exercise is that the token exchange happens, not that
 * the client is who it claims to be.
 *
 * <p>The token is recorded in {@link TokenStore} and IS validated on the hearing endpoints: a
 * caller that skips this exchange, or reuses a token past {@code expires_in}, gets 401. See
 * ADR-004.
 */
@RestController
public class AuthController {

    private final TokenStore tokenStore;

    public AuthController(final TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @PostMapping(path = "/auth/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public OAuthTokenResponse issueToken() {
        return new OAuthTokenResponse(tokenStore.issue(), "Bearer", TokenStore.EXPIRES_IN_SECONDS, null);
    }
}
