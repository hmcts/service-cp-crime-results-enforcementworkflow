package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Test vectors shared with cpp-context-hearing-nows (contract hearingnows-add-enforcement-nows-data.md, research R18). */
class ShareCorrelationIdTest {

    private static final UUID HEARING_ID = UUID.fromString("04180ff1-99b0-40b7-9929-ca05bdc767d8");

    @Test
    void should_match_the_contract_test_vectors() {
        assertThat(ShareCorrelationId.forShare(HEARING_ID, LocalDate.parse("2026-05-03")))
                .isEqualTo(UUID.fromString("91f9490e-846c-3c3b-b827-1eac49327338"));
        assertThat(ShareCorrelationId.forShare(HEARING_ID, LocalDate.parse("2026-05-10")))
                .isEqualTo(UUID.fromString("74ce7cd2-9d92-31a6-8b7a-ecc365d96b9b"));
    }

    @Test
    void different_hearing_days_should_give_different_ids() {
        assertThat(ShareCorrelationId.forShare(HEARING_ID, LocalDate.parse("2026-05-03")))
                .isNotEqualTo(ShareCorrelationId.forShare(HEARING_ID, LocalDate.parse("2026-05-04")));
    }
}
