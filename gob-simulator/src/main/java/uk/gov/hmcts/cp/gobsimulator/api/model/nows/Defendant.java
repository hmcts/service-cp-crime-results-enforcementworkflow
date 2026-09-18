package uk.gov.hmcts.cp.gobsimulator.api.model.nows;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema component {@code Defendant} in libra-gateway-hearing-events-v0.4.0.yml.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Defendant(
        String defName,
        String nationalInsuranceNumber,
        String DoB,
        String homeTelNo,
        String businessTelNo,
        String mobileTelNo,
        String email1,
        String email2,
        String assetVehicleReg,
        String assetVehicleMake,
        String alias1,
        String alias2,
        String alias3,
        String alias4,
        String alias5,
        DefAddress defAddress,
        AccountNotes accountNotes,
        ImposingCourt imposingCourt,
        ParentGuardian parentGuardian,
        Integer daysInDefault) {
}
