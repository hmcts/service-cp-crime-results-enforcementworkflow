package uk.gov.hmcts.cp.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import uk.gov.hmcts.cp.config.ClientProperties;

import java.util.Optional;

/**
 * Resolves the team inbox for a Division Code (feature 005, FR-003, contracts/enforcement-area.md - OPEN DEPENDENCY,
 * CIMD-4389, plan.md D1): mirrors {@link ReferenceDataClient}'s 404-vs-error distinction. A confirmed no-match (404)
 * is {@link Optional#empty()} (never cached - a team inbox added later must be picked up); any other failure throws
 * {@link EnforcementAreaUnavailableException}, which MUST be retried, never read as "no match".
 */
@Slf4j
@Component
public class EnforcementAreaClient {

    /* default */ static final String TEAM_INBOX_PATH =
            "/reference-data-query-api/query/api/rest/enforcement-area/internal/{divisionCode}";

    private final RestClient restClient;
    private final String cjscppuid;

    public EnforcementAreaClient(@Qualifier("enforcementAreaRestClientBuilder") final RestClient.Builder restClientBuilder,
                                 final ClientProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getEnforcementArea().getBaseUrl()).build();
        this.cjscppuid = properties.getEnforcementArea().getCjscppuid();
    }

    /** @throws EnforcementAreaUnavailableException when the lookup can't be answered (never a confirmed no-match) */
    public Optional<TeamInbox> findTeamInbox(final int divisionCode) {
        Optional<TeamInbox> inbox;
        try {
            final TeamInboxResponse response = restClient.get()
                    .uri(uri -> uri.path(TEAM_INBOX_PATH).build(divisionCode))
                    .header("CJSCPPUID", cjscppuid)
                    .retrieve()
                    .body(TeamInboxResponse.class);
            inbox = Optional.ofNullable(response).map(r -> new TeamInbox(r.teamEmail()));
        } catch (HttpClientErrorException.NotFound e) {
            log.info("No enforcement_area team inbox for Division Code {}", divisionCode);
            inbox = Optional.empty();
        } catch (RestClientResponseException e) {
            log.warn("enforcement_area lookup failed for Division Code {}: HTTP {} {}", divisionCode,
                    e.getStatusCode().value(), e.getStatusText());
            throw new EnforcementAreaUnavailableException("enforcement_area lookup failed for Division Code "
                    + divisionCode + ": " + e.getClass().getSimpleName(), e);
        } catch (RestClientException e) {
            log.warn("enforcement_area lookup failed for Division Code {}: {} - {}", divisionCode,
                    e.getClass().getSimpleName(), e.getMessage());
            throw new EnforcementAreaUnavailableException("enforcement_area lookup failed for Division Code "
                    + divisionCode + ": " + e.getClass().getSimpleName(), e);
        }
        return inbox;
    }

    /* default */ record TeamInboxResponse(String divisionCode, String teamEmail) {
    }
}
