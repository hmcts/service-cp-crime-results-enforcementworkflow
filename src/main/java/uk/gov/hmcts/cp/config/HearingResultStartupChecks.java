package uk.gov.hmcts.cp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Makes a gap in the configuration visible at startup (constitution Principle IX): if any payment terms prompt reference
 * or value format is left empty (feature 004, CIMD-4251), payment terms results are recorded as unsendable.
 * Names the empty keys only, never a value (research R9).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HearingResultStartupChecks {

    /* default */ static final String PAYMENT_TERMS_WARNING =
            "payment terms results will be recorded as unsendable until configured (CIMD-4251)";

    private final PaymentTermsProperties paymentTerms;

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfPaymentTermsUnconfigured() {
        final List<String> empty = paymentTerms.emptyKeys();
        if (!empty.isEmpty()) {
            log.warn("cp.hearing-result.payment-terms not configured: {}. Empty keys: {}", PAYMENT_TERMS_WARNING, empty);
        }
    }
}
