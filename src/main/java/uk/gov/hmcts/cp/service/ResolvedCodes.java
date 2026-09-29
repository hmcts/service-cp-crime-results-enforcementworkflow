package uk.gov.hmcts.cp.service;

import uk.gov.hmcts.cp.openapi.model.HearingResult.ResultCodeEnum;

import java.util.List;
import java.util.Set;

/**
 * The defendant's result codes. {@code cpShortCodes} are CP's shortCodes as looked up (distinct, in
 * order), which key the NOWS data-item mapping. {@code gobCodes} are the codes sent to GOB, after the
 * rename and the enum filter. {@code droppedCodes} are the codes left out, for logging.
 */
public record ResolvedCodes(List<String> cpShortCodes, Set<ResultCodeEnum> gobCodes, List<String> droppedCodes) {
}
