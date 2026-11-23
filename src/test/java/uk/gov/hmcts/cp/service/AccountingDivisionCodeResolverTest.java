package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.client.EnforcementAreaUnavailableException;
import uk.gov.hmcts.cp.client.ReferenceDataClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CIMD-4640: the defendant's postcode first, else the hearing court's LJA, as the NOWS functions do. */
class AccountingDivisionCodeResolverTest {

    private final ReferenceDataClient client = mock(ReferenceDataClient.class);
    private final AccountingDivisionCodeResolver resolver = new AccountingDivisionCodeResolver(client);

    @Test
    void postcode_with_an_enforcement_area_should_give_its_code() {
        when(client.findAccountDivisionCodeByPostcode("XX1 1XX")).thenReturn(Optional.of(26));

        assertThat(resolver.resolve(new EnforcementAreaQuery("XX1 1XX", "9999"))).contains(26);
        verify(client, never()).findAccountDivisionCodeByLjaCode(anyString());
    }

    @Test
    void postcode_without_an_enforcement_area_should_fall_back_to_the_lja() {
        when(client.findAccountDivisionCodeByPostcode("XX1 1XX")).thenReturn(Optional.empty());
        when(client.findAccountDivisionCodeByLjaCode("9999")).thenReturn(Optional.of(31));

        assertThat(resolver.resolve(new EnforcementAreaQuery("XX1 1XX", "9999"))).contains(31);
    }

    @Test
    void no_postcode_should_use_the_lja() {
        when(client.findAccountDivisionCodeByLjaCode("9999")).thenReturn(Optional.of(31));

        assertThat(resolver.resolve(new EnforcementAreaQuery(" ", "9999"))).contains(31);
        verify(client, never()).findAccountDivisionCodeByPostcode(anyString());
    }

    @Test
    void neither_should_give_no_code_without_asking_reference_data() {
        assertThat(resolver.resolve(new EnforcementAreaQuery(null, null))).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void no_enforcement_area_for_either_should_give_no_code() {
        when(client.findAccountDivisionCodeByPostcode("XX1 1XX")).thenReturn(Optional.empty());
        when(client.findAccountDivisionCodeByLjaCode("9999")).thenReturn(Optional.empty());

        assertThat(resolver.resolve(new EnforcementAreaQuery("XX1 1XX", "9999"))).isEmpty();
    }

    // reference data unavailable is not "no enforcement area": it is thrown, so the stage is retried
    @Test
    void unavailable_reference_data_should_be_thrown() {
        when(client.findAccountDivisionCodeByPostcode("XX1 1XX"))
                .thenThrow(new EnforcementAreaUnavailableException("postcode", new IllegalStateException()));

        assertThatThrownBy(() -> resolver.resolve(new EnforcementAreaQuery("XX1 1XX", "9999")))
                .isInstanceOf(EnforcementAreaUnavailableException.class);
        verify(client, never()).findAccountDivisionCodeByLjaCode(anyString());
    }
}
