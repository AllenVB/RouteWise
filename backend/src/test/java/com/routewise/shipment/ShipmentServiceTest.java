package com.routewise.shipment;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.routewise.address.Address;
import com.routewise.address.AddressRepository;
import com.routewise.address.VerificationStatus;
import com.routewise.common.error.ApiException;
import com.routewise.recipient.RecipientRepository;
import com.routewise.shipment.ShipmentDtos.CreateRequest;
import com.routewise.shipment.ShipmentDtos.UpdateRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ShipmentServiceTest {

    private ShipmentRepository shipments;
    private AddressRepository addresses;
    private RecipientRepository recipients;
    private OrderNumberGenerator orderNumbers;
    private ShipmentService service;

    private Address address;

    @BeforeEach
    void setUp() {
        shipments = mock(ShipmentRepository.class);
        addresses = mock(AddressRepository.class);
        recipients = mock(RecipientRepository.class);
        orderNumbers = mock(OrderNumberGenerator.class);
        service = new ShipmentService(shipments, addresses, recipients, orderNumbers);

        address = mock(Address.class);
        when(address.getRecipientId()).thenReturn(12L);
        when(address.getVerificationStatus()).thenReturn(VerificationStatus.DOGRULANDI);

        when(recipients.existsById(12L)).thenReturn(true);
        when(addresses.findById(31L)).thenReturn(Optional.of(address));
        when(orderNumbers.next()).thenReturn("BND-2026-00417");
        when(shipments.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static CreateRequest request() {
        return new CreateRequest(12L, 31L, new BigDecimal("4.2"), (short) 2, 5);
    }

    @Nested
    @DisplayName("Oluşturma")
    class Create {

        @Test
        @DisplayName("Sipariş numarasını sunucu üretir")
        void shouldGenerateOrderNumber() {
            Shipment saved = service.create(request());

            assertThat(saved.getOrderNumber()).isEqualTo("BND-2026-00417");
            verify(orderNumbers).next();
        }

        @Test
        @DisplayName("Varsayılan durum BEKLEMEDE")
        void shouldStartPending() {
            assertThat(service.create(request()).getStatus()).isEqualTo(ShipmentStatus.BEKLEMEDE);
        }

        @Test
        @DisplayName("Öncelik verilmezse 0, servis süresi verilmezse 5 dakika")
        void shouldApplyDefaults() {
            var req = new CreateRequest(12L, 31L, new BigDecimal("1.0"), null, null);

            Shipment saved = service.create(req);

            assertThat(saved.getPriority()).isZero();
            assertThat(saved.getServiceTimeMin()).isEqualTo(5);
        }

        @Test
        @DisplayName("Doğrulanmamış adresle kargo oluşturulamaz")
        void shouldRejectUnverifiedAddress() {
            when(address.getVerificationStatus())
                    .thenReturn(VerificationStatus.DOGRULAMA_BEKLIYOR);

            assertThatThrownBy(() -> service.create(request()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("doğrulanmadı");
            verify(shipments, never()).save(any());
        }

        @Test
        @DisplayName("Reddedilmiş adresle de oluşturulamaz")
        void shouldRejectRejectedAddress() {
            when(address.getVerificationStatus()).thenReturn(VerificationStatus.REDDEDILDI);

            assertThatThrownBy(() -> service.create(request()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("Adres başka alıcıya aitse reddedilir")
        void shouldRejectForeignAddress() {
            when(address.getRecipientId()).thenReturn(99L);

            assertThatThrownBy(() -> service.create(request()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("bu alıcıya ait değil");
        }

        @Test
        @DisplayName("Olmayan alıcı için 404")
        void shouldRejectUnknownRecipient() {
            when(recipients.existsById(12L)).thenReturn(false);

            assertThatThrownBy(() -> service.create(request()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Alıcı");
        }

        @Test
        @DisplayName("Olmayan adres için 404")
        void shouldRejectUnknownAddress() {
            when(addresses.findById(31L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(request()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Adres");
        }
    }

    @Nested
    @DisplayName("Güncelleme")
    class Update {

        @Test
        @DisplayName("Yalnızca verilen alanlar değişir")
        void shouldApplyPartialUpdate() {
            Shipment s = new Shipment("BND-2026-1", 12L, 31L, new BigDecimal("4.2"), (short) 2, 5);
            when(shipments.findById(1L)).thenReturn(Optional.of(s));

            service.update(1L, new UpdateRequest(new BigDecimal("9.9"), null, null));

            assertThat(s.getWeightKg()).isEqualByComparingTo("9.9");
            assertThat(s.getPriority()).isEqualTo((short) 2);   // dokunulmadı
            assertThat(s.getServiceTimeMin()).isEqualTo(5);
        }

        @Test
        @DisplayName("Yola çıkmış kargo güncellenemez")
        void shouldRejectWhenInTransit() {
            Shipment s = mock(Shipment.class);
            when(s.getStatus()).thenReturn(ShipmentStatus.YOLDA);
            when(shipments.findById(1L)).thenReturn(Optional.of(s));

            assertThatThrownBy(() -> service.update(1L,
                    new UpdateRequest(new BigDecimal("1.0"), null, null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("yola çıktıktan sonra");
        }
    }

    @Nested
    @DisplayName("Silme")
    class Delete {

        @Test
        @DisplayName("Planlanmamış kargo silinir")
        void shouldDeletePending() {
            Shipment s = new Shipment("BND-2026-1", 12L, 31L, new BigDecimal("1"), (short) 0, 5);
            when(shipments.findById(1L)).thenReturn(Optional.of(s));

            service.delete(1L);

            verify(shipments).delete(s);
        }

        @Test
        @DisplayName("Planlanmış kargo silinemez — rotayı bozar")
        void shouldRejectPlanned() {
            Shipment s = mock(Shipment.class);
            when(s.getStatus()).thenReturn(ShipmentStatus.PLANLANDI);
            when(shipments.findById(1L)).thenReturn(Optional.of(s));

            assertThatThrownBy(() -> service.delete(1L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Planlanmış kargo silinemez");
            // delete(T) ile delete(Specification) aşırı yüklemesi arasında
            // seçim yapılması gerekiyor: JpaSpecificationExecutor ikisini de sunar.
            verify(shipments, never()).delete(any(Shipment.class));
        }
    }

    @Nested
    @DisplayName("Arama metni")
    class QueryNormalization {

        @Test
        @DisplayName("Boş veya yalnızca boşluk filtre sayılmaz")
        void shouldTreatBlankAsNoFilter() {
            assertThat(ShipmentService.normalizeQuery(null)).isNull();
            assertThat(ShipmentService.normalizeQuery("")).isNull();
            assertThat(ShipmentService.normalizeQuery("   ")).isNull();
        }

        @Test
        @DisplayName("Baştaki ve sondaki boşluklar kırpılır")
        void shouldTrim() {
            assertThat(ShipmentService.normalizeQuery("  BND-2026  ")).isEqualTo("BND-2026");
        }
    }
}
