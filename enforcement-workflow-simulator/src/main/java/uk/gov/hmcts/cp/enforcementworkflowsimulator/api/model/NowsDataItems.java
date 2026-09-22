package uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.CtBankDetails;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.Defendant;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.Offences;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.PaymentHistory;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.Terms;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.TransactionHistory;
import uk.gov.hmcts.cp.enforcementworkflowsimulator.api.model.nows.WarrantContactDetails;

/**
 * The NOWS logical entities returned by GoB. Component names mirror
 * {@code NowsDataItems} in libra-gateway-hearing-events-v0.4.0.yml exactly; the schema is
 * {@code additionalProperties: false}, so nothing outside this list may ever be emitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NowsDataItems(
        Defendant defendant,
        PaymentHistory paymentHistory,
        TransactionHistory transactionHistory,
        Offences offences,
        Terms terms,
        BigDecimal accountBalance,
        String accountWarrantNumber,
        WarrantContactDetails warrantContactDetails,
        BigDecimal accountBailAmount,
        CtBankDetails ctBankDetails,
        Integer daysBeforeReleaseWarrant,
        String accountNumber,
        String accountDateImposed) {
}
