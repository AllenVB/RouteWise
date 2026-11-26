package com.routewise.recipient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.routewise.address.AddressRepository;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;

class RecipientServiceTest {

    private final RecipientRepository recipients = mock(RecipientRepository.class);
    private final AddressRepository addresses = mock(AddressRepository.class);
    private final RecipientService service = new RecipientService(recipients, addresses);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("Boş arama filtre sayılmaz — sorgu null parametreyle hiç çağrılmaz")
    void blankQueryBecomesEmptyString(String q) {
        service.search(q, 0, 25);

        verify(recipients).search("", PageRequest.of(0, 25));
    }

    @Test
    @DisplayName("Kullanıcının yazdığı LIKE joker karakterleri filtreyi genişletmez")
    void wildcardsAreStripped() {
        assertThat(RecipientService.normalize(" %_elif ")).isEqualTo("elif");
    }

    @Test
    @DisplayName("Bilinmeyen alıcının adresleri boş liste değil 404'tür")
    void unknownRecipientIsNotFound() {
        when(recipients.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addressesOf(9L))
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(addresses, never()).findByRecipientIdOrderByIdAsc(9L);
    }
}
