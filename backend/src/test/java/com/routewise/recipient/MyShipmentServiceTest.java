package com.routewise.recipient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.routewise.address.AddressRepository;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.RouteStop;
import com.routewise.optimization.RouteStopRepository;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** A2/A3 — rol sızıntısı ve "hangi ETA" kuralı. */
class MyShipmentServiceTest {

    private final RecipientRepository recipients = mock(RecipientRepository.class);
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final AddressRepository addresses = mock(AddressRepository.class);
    private final RouteStopRepository stops = mock(RouteStopRepository.class);
    private final MyShipmentService service = new MyShipmentService(recipients, shipments, addresses, stops);

    private Shipment mine;

    private static <T> T withId(T e, long id) {
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @BeforeEach
    void setUp() {
        Recipient me = mock(Recipient.class);
        when(me.getId()).thenReturn(12L);
        when(recipients.findByUserId(3L)).thenReturn(Optional.of(me));
        mine = withId(new Shipment("BND-2026-00051", 12L, 31L, BigDecimal.ONE, (short) 0, 5), 51L);
        Shipment others = withId(new Shipment("BND-2026-00099", 77L, 41L, BigDecimal.ONE, (short) 0, 5), 99L);
        when(shipments.findById(51L)).thenReturn(Optional.of(mine));
        when(shipments.findById(99L)).thenReturn(Optional.of(others));
        when(addresses.findAllById(anyList())).thenReturn(List.of());
    }

    @Test
    @DisplayName("Başkasının kargosu 404, var olmayan kargo da 404 — ayırt edilemez")
    void othersShipmentIsNotFound() {
        when(shipments.findById(1234L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(3L, 99L))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service.get(3L, 1234L)).hasMessage("Kargo bulunamadı.");
        assertThatThrownBy(() -> service.get(3L, 99L)).hasMessage("Kargo bulunamadı.");
    }

    @Test
    @DisplayName("Sevk edilmemiş kargoda ETA yok")
    void noEtaBeforeDispatch() {
        when(stops.findDispatchedForShipments(List.of(51L))).thenReturn(List.of());

        assertThat(service.get(3L, 51L).eta()).isNull();
    }

    @Test
    @DisplayName("Yeniden planlanan kargoda en yeni durağın saati gösterilir (sorgu yeniden eskiye döner)")
    void newestStopWins() {
        RouteStop today = new RouteStop(8L, 51L, 2, 1L, BigDecimal.ONE, 1, Instant.parse("2026-11-03T06:00:00Z"));
        RouteStop yesterday = new RouteStop(7L, 51L, 1, 1L, BigDecimal.ONE, 1, Instant.parse("2026-11-02T06:00:00Z"));
        yesterday.fail(Instant.parse("2026-11-02T06:05:00Z"), "Kimse yok");
        when(stops.findDispatchedForShipments(List.of(51L))).thenReturn(List.of(today, yesterday));

        var view = service.get(3L, 51L);

        assertThat(view.eta()).isEqualTo(Instant.parse("2026-11-03T06:00:00Z"));
        assertThat(view.stopStatus()).isEqualTo("BEKLEMEDE");
        assertThat(view.failureReason()).isNull();
    }

    @Test
    @DisplayName("Alıcı profili yoksa 404")
    void noRecipientProfile() {
        when(recipients.findByUserId(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(9L))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
