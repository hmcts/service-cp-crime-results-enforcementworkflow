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
 * {@link TeamInboxUnavailableException}, which MUST be retried, never read as "no match".
 *
 * <p>{@code enforcement_area} is just another endpoint on the reference-data service (same host that already serves
 * {@link ReferenceDataClient}'s own {@code enforcement-area} accounting-division-code lookup, CIMD-4640) - not a
 * separate target system, so this reuses {@code cp.reference-data.*} (base-url, cjscppuid) rather than a duplicate
 * {@code cp.enforcement-area.*} config section.
 */
@Slf4j
@Component
public class EnforcementAreaClient {

    /* default */ static final String TEAM_INBOX_PATH =
            "/reference-data-query-api/query/api/rest/enforcement-area/internal/{divisionCode}";

    private final RestClient restClient;
    private final String cjscppuid;

    public EnforcementAreaClient(@Qualifier("referenceDataRestClientBuilder") final RestClient.Builder restClientBuilder,
                                 final ClientProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getReferenceData().getBaseUrl()).build();
        this.cjscppuid = properties.getReferenceData().getCjscppuid();
    }

    /** @throws TeamInboxUnavailableException when the lookup can't be answered (never a confirmed no-match) */
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
            throw new TeamInboxUnavailableException("enforcement_area lookup failed for Division Code "
                    + divisionCode + ": " + e.getClass().getSimpleName(), e);
        } catch (RestClientException e) {
            log.warn("enforcement_area lookup failed for Division Code {}: {} - {}", divisionCode,
                    e.getClass().getSimpleName(), e.getMessage());
            throw new TeamInboxUnavailableException("enforcement_area lookup failed for Division Code "
                    + divisionCode + ": " + e.getClass().getSimpleName(), e);
        }
        return inbox;
    }

    /* default */ record TeamInboxResponse(String divisionCode, String teamEmail) {
    }
}
