package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Feature 003 (FR-012): the account number published is the prosecutorDefendantId sent to GOB, passed through. */
class GobRepliesTest {

    @Test
    void account_number_should_be_the_prosecutor_defendant_id_as_sent() {
        assertThat(GobReplies.accountNumberOf("{\"defendantDetails\":{\"prosecutorDefendantId\":\" 26000123a \"}}"))
                .contains(" 26000123a ");
    }

    @Test
    void missing_non_text_or_unreadable_values_should_give_no_account_number() {
        assertThat(GobReplies.accountNumberOf(null)).isEmpty();
        assertThat(GobReplies.accountNumberOf("not json")).isEmpty();
        assertThat(GobReplies.accountNumberOf("{\"defendantDetails\":{}}")).isEmpty();
        assertThat(GobReplies.accountNumberOf("{\"defendantDetails\":{\"prosecutorDefendantId\":123}}")).isEmpty();
        assertThat(GobReplies.accountNumberOf("{\"defendantDetails\":{\"prosecutorDefendantId\":\" \"}}")).isEmpty();
    }
}
