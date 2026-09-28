package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code Offence} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Offence(
        String dateImposed,
        String caseNumber,
        String offenceCode,
        String offenceTitle,
        String cymraeg_offenceTitle,
        String ticketNumber,
        String centralTicketOfficeName,
        String vehicleReg,
        String timeOfOffence,
        String placeOfOffence,
        String noticeToOwnerNoticeToHirer,
        String dateIssued,
        String licenceNo,
        Impositions impositions,
        BigDecimal offenceTotal) {
}
