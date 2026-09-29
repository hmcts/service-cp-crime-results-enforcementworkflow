package uk.gov.hmcts.cp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Makes the less safe test aids visible whenever they are switched on (constitution Principle IX). */
@Slf4j
@Component
@RequiredArgsConstructor
public class HearingResultStartupChecks {

    /* default */ static final String PLACEHOLDER_WARNING = "placeholder paymentDueDate enabled – not for production";

    private final HearingResultProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfPlaceholdersEnabled() {
        if (properties.getPaymentDueDateFallback() == PaymentDueDateFallback.HEARING_DATE) {
            log.warn("cp.hearing-result.payment-due-date-fallback=HEARING_DATE: {} (research.md R13)", PLACEHOLDER_WARNING);
        }
    }
}
