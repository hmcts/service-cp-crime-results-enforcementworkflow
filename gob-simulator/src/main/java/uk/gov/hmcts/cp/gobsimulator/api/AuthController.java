package uk.gov.hmcts.cp.gobsimulator.api;

import java.util.Base64;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import uk.gov.hmcts.cp.gobsimulator.api.model.OAuthTokenResponse;

/**
 * Issues an opaque dummy bearer token. Credentials are never checked and the token is never
 * validated on any other endpoint — the simulator carries no security (spec §2).
 */
@RestController
public class AuthController {

    private static final int EXPIRES_IN_SECONDS = 3600;

    @PostMapping(path = "/auth/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public OAuthTokenResponse issueToken() {
        final String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new OAuthTokenResponse(token, "Bearer", EXPIRES_IN_SECONDS, null);
    }
}
