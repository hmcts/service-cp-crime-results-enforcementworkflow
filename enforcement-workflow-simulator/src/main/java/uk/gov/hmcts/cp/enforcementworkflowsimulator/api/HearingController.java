package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.HearingConfirmedRequest;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.HearingResultedRequest;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.HearingResultedResponse;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.assembly.DefendantDetailsOverlay;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.assembly.NowsDataItemsAssembler;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue.Catalogue;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue.CatalogueLoader;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.idempotency.IdempotencyCache;

@Slf4j
@RestController
public class HearingController {

    private final NowsDataItemsAssembler assembler;
    private final DefendantDetailsOverlay defendantDetailsOverlay;
    private final IdempotencyCache idempotencyCache;
    private final Catalogue catalogue;

    /**
     * The bundled contract's {@code resultCode} enum, read once at construction via {@link
     * CatalogueLoader#schemaResultCodes()} — the same parsing path startup validation and {@code
     * CatalogueCoverageTest} use — rather than hand-copied (Task 8, ruling 3).
     */
    private final Set<String> schemaResultCodes;

    public HearingController(final NowsDataItemsAssembler assembler,
                              final DefendantDetailsOverlay defendantDetailsOverlay,
                              final IdempotencyCache idempotencyCache,
                              final Catalogue catalogue,
                              final CatalogueLoader catalogueLoader) {
        this.assembler = assembler;
        this.defendantDetailsOverlay = defendantDetailsOverlay;
        this.idempotencyCache = idempotencyCache;
        this.catalogue = catalogue;
        this.schemaResultCodes = catalogueLoader.schemaResultCodes();
    }

    @PostMapping(path = "/hearing", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> confirmHearing(@Valid @RequestBody final HearingConfirmedRequest request) {
        log.info("Hearing confirmation accepted: caseUrn={}, courtHearingLocation={}",
                request.caseUrn(), request.courtHearingLocation());
        return ResponseEntity.ok().build();
    }

    @PostMapping(path = "/hearing/result",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public HearingResultedResponse resultHearing(
            @Valid @RequestBody final HearingResultedRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) final String correlationId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) final String idempotencyKey) {

        validateRequestedNames(request);
        validateResultCodes(request);

        final Optional<HearingResultedResponse> cached = idempotencyCache.get(idempotencyKey, request);
        final HearingResultedResponse response = cached.orElseGet(() -> buildResponse(request, correlationId));

        if (cached.isEmpty()) {
            idempotencyCache.put(idempotencyKey, request, response);
            log.info("Hearing result processed: caseUrn={}, idempotencyKey={}",
                    request.caseUrn(), idempotencyKey);
        } else {
            log.info("Replaying cached response: caseUrn={}, idempotencyKey={}",
                    request.caseUrn(), idempotencyKey);
        }
        return response;
    }

    /**
     * Rejects a NowsDataItemName the contract does not declare with 400, rather than letting it
     * reach {@link Catalogue#propertiesFor} and surface as a 500.
     */
    private void validateRequestedNames(final HearingResultedRequest request) {
        for (final HearingResultedRequest.NowsDataItemRequest item : request.nowsDataRequest().nowsDataItems()) {
            if (!catalogue.isKnownNowsDataItemName(item.name())) {
                throw new UnknownNowsDataItemNameException(item.name());
            }
        }
    }

    /**
     * Rejects a resultCode the bundled contract's enum does not declare with 400, rather than
     * letting it reach {@link Catalogue#fieldsFor} and surface as a 500 (Task 8, ruling 3 — the
     * same defect class {@link #validateRequestedNames} fixed for {@code NowsDataItemName}).
     */
    private void validateResultCodes(final HearingResultedRequest request) {
        for (final HearingResultedRequest.HearingResult result : request.results()) {
            if (!schemaResultCodes.contains(result.resultCode())) {
                throw new UnknownResultCodeException(result.resultCode());
            }
        }
    }

    private HearingResultedResponse buildResponse(final HearingResultedRequest request, final String correlationId) {
        final List<String> resultCodes = request.results().stream()
                .map(HearingResultedRequest.HearingResult::resultCode)
                .toList();
        final List<String> requestedNames = request.nowsDataRequest().nowsDataItems().stream()
                .map(HearingResultedRequest.NowsDataItemRequest::name)
                .toList();
        return new HearingResultedResponse(
                request.caseUrn(),
                currentTimestamp(),
                correlationId,
                assembler.assemble(request.caseUrn(), resultCodes, requestedNames,
                        defendantDetailsOverlay.from(request.defendantDetails())));
    }

    private static String currentTimestamp() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
