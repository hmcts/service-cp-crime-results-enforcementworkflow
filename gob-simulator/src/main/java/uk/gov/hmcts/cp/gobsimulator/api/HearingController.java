package uk.gov.hmcts.cp.gobsimulator.api;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import uk.gov.hmcts.cp.gobsimulator.api.model.HearingConfirmedRequest;

@Slf4j
@RestController
public class HearingController {

    @PostMapping(path = "/hearing", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> confirmHearing(@Valid @RequestBody final HearingConfirmedRequest request) {
        log.info("Hearing confirmation accepted: caseUrn={}, courtHearingLocation={}",
                request.caseUrn(), request.courtHearingLocation());
        return ResponseEntity.ok().build();
    }
}
