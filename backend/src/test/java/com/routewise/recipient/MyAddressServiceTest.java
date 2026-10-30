package com.routewise.recipient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import com.routewise.address.Address;
import com.routewise.address.AddressDtos.Coordinate;
import com.routewise.address.AddressDtos.CreateAddressRequest;
import com.routewise.address.AddressRepository;
import com.routewise.address.AddressService;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.common.geo.Points;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Role leakage on the recipient's own addresses: another's address must look nonexistent. */
class MyAddressServiceTest {

    private static final CreateAddressRequest MOVE =
            new CreateAddressRequest("Yeni", null, null, new Coordinate(40.35, 27.97));

    private final RecipientRepository recipients = mock(RecipientRepository.class);
    private final AddressRepository addresses = mock(AddressRepository.class);
    private final AddressService addressService = mock(AddressService.class);
    private final MyAddressService service = new MyAddressService(recipients, addresses, addressService);

    @BeforeEach
    void setUp() {
        Recipient me = mock(Recipient.class);
        when(me.getId()).thenReturn(12L);
        when(recipients.findByUserId(3L)).thenReturn(Optional.of(me));

        Address someoneElses = new Address(99L, "Başkası", null, null, Points.of(40.35, 27.97), 1L, BigDecimal.ONE);
        ReflectionTestUtils.setField(someoneElses, "id", 31L);
        when(addresses.findById(31L)).thenReturn(Optional.of(someoneElses));
    }

    @Test
    @DisplayName("Başkasının adresini taşımak 404 — 403 değil, kayıt var olduğunu sızdırmaz")
    void otherRecipientsAddressIsNotFound() {
        assertThatThrownBy(() -> service.relocate(3L, 31L, MOVE))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(addressService, never()).relocate(anyLong(), any());
    }

    @Test
    @DisplayName("Var olmayan adres de aynı 404'ü verir — ikisi ayırt edilemez")
    void missingAddressLooksTheSame() {
        when(addresses.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.relocate(3L, 77L, MOVE))
                .hasMessage("Adres bulunamadı.");
        assertThatThrownBy(() -> service.relocate(3L, 31L, MOVE))
                .hasMessage("Adres bulunamadı.");
    }

    @Test
    @DisplayName("Yeni adres her zaman çağıranın alıcı profiline yazılır")
    void createUsesCallersRecipient() {
        service.create(3L, MOVE);

        verify(addressService).create(12L, MOVE);
    }
}
