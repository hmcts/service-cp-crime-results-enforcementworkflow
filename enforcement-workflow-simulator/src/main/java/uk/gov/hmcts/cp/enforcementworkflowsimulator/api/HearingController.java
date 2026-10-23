package uk.gov.hmcts.cp.enforcementworkflowsimulator.api;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.NowsDataItems;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.assembly.DefendantDetailsOverlay;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.assembly.NowsDataItemsAssembler;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue.Catalogue;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.idempotency.IdempotencyCache;

@Slf4j
@RestController
public class HearingController {

    private final NowsDataItemsAssembler assembler;
    private final DefendantDetailsOverlay defendantDetailsOverlay;
    private final IdempotencyCache idempotencyCache;
    private final Catalogue catalogue;

    public HearingController(final NowsDataItemsAssembler assembler,
                              final DefendantDetailsOverlay defendantDetailsOverlay,
                              final IdempotencyCache idempotencyCache,
                              final Catalogue catalogue) {
        this.assembler = assembler;
        this.defendantDetailsOverlay = defendantDetailsOverlay;
        this.idempotencyCache = idempotencyCache;
        this.catalogue = catalogue;
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

        // resultCode is deliberately NOT checked against a list here: since CIMD-4530
        // (2026-10-22) every CP result code is sent. Its only contract constraint (1..10 chars) is
        // bean validation on HearingResultedRequest.HearingResult; a code with no catalogue row
        // contributes no extra NOWS data fields (Catalogue.fieldsForPostedCode).
        validateRequestedNames(request);

        final Optional<HearingResultedResponse> cached = idempotencyCache.get(idempotencyKey, request);
        final HearingResultedResponse response = cached.orElseGet(() -> buildResponse(request, correlationId));
        final String safeIdempotencyKey = sanitizeForLog(idempotencyKey);

        if (cached.isEmpty()) {
            idempotencyCache.put(idempotencyKey, request, response);
            log.info("Hearing result processed: caseUrn={}, idempotencyKey={}",
                    request.caseUrn(), safeIdempotencyKey);
        } else {
            log.info("Replaying cached response: caseUrn={}, idempotencyKey={}",
                    request.caseUrn(), safeIdempotencyKey);
        }
        return response;
    }

    private String sanitizeForLog(final String value) {
        return value == null ? null : value.replace('\r', '_').replace('\n', '_');
    }

    /**
     * Rejects a NowsDataItemName the contract does not declare with 400, rather than letting it
     * reach {@link Catalogue#propertiesFor} and surface as a 500.
     */
    private void validateRequestedNames(final HearingResultedRequest request) {
        for (final String name : request.requestedNowsDataItemNames()) {
            if (!catalogue.isKnownNowsDataItemName(name)) {
                throw new UnknownNowsDataItemNameException(name);
            }
        }
    }

    /**
     * When the caller requests no NOWS entities, {@code nowsDataItems} is left null so the
     * response omits it entirely (optional since v0.6.0) rather than sending an empty object.
     */
    private HearingResultedResponse buildResponse(final HearingResultedRequest request, final String correlationId) {
        final List<String> resultCodes = request.results().stream()
                .map(HearingResultedRequest.HearingResult::resultCode)
                .toList();
        final List<String> requestedNames = request.requestedNowsDataItemNames();
        final NowsDataItems nowsDataItems = requestedNames.isEmpty()
                ? null
                : assembler.assemble(request.caseUrn(), resultCodes, requestedNames,
                        defendantDetailsOverlay.from(request.defendantDetails()));
        return new HearingResultedResponse(request.caseUrn(), currentTimestamp(), correlationId, nowsDataItems);
    }

    private static String currentTimestamp() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
