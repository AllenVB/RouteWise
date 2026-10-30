package com.routewise.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.routewise.address.AddressDtos.Coordinate;
import com.routewise.address.AddressDtos.CreateAddressRequest;
import com.routewise.address.AddressDtos.ValidationResult;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.common.geo.Points;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AddressServiceTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);

    private static final ValidationResult OK = new ValidationResult(
            true, 48219L, 7.4, "1204. Sokak", 1800.0, false, true, List.of(), List.of());
    private static final ValidationResult OUTSIDE = new ValidationResult(
            false, null, null, null, null, false, false, List.of(),
            List.of("Nokta Bandırma sınırı dışında."));
    private static final ValidationResult FAR_FROM_ROAD = new ValidationResult(
            true, 48219L, 142.0, null, null, false, false, List.of(),
            List.of("Nokta en yakın yola 142 m uzakta."));

    private static final CreateAddressRequest MOVE = new CreateAddressRequest(
            "Çınarlı Mah., 290 Sk. No:6", "Bandırma", "Çınarlı", new Coordinate(40.3488, 27.9712));

    private AddressRepository addresses;
    private AddressValidator validator;
    private Address address;

    @BeforeEach
    void setUp() {
        addresses = mock(AddressRepository.class);
        validator = mock(AddressValidator.class);
        address = new Address(12L, "Yeni Mahalle, 1204 Sk. No:7", "Bandırma", "Yeni Mahalle",
                Points.of(40.3521, 27.9767), 48219L, new BigDecimal("7.40"));

        when(addresses.findById(31L)).thenReturn(Optional.of(address));
        when(addresses.save(any(Address.class))).thenAnswer(inv -> inv.getArgument(0));
        when(validator.validate(anyDouble(), anyDouble(), any())).thenReturn(OK);
    }

    /** Servis, verilen Istanbul yerel saatinde "bugün"ü görür. */
    private AddressService serviceAt(String istanbulLocalDateTime) {
        Instant now = java.time.LocalDateTime.parse(istanbulLocalDateTime).atZone(ISTANBUL).toInstant();
        return new AddressService(addresses, validator,
                new AddressValidationProperties(50.0, 5000.0, 2), Clock.fixed(now, ISTANBUL));
    }

    @Nested
    @DisplayName("relocate — günlük değişiklik limiti")
    class DailyLimit {

        @Test
        @DisplayName("Limit dolunca o gün üçüncü değişiklik reddedilir")
        void shouldRejectWhenLimitReachedToday() {
            AddressService service = serviceAt("2026-10-30T10:00");
            service.relocate(31L, MOVE);
            service.relocate(31L, MOVE);

            assertThatThrownBy(() -> service.relocate(31L, MOVE))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ErrorCode.CHANGE_LIMIT_REACHED);
        }

        @Test
        @DisplayName("Ertesi gün hak yenilenir — hata buydu: adres kalıcı kilitleniyordu")
        void shouldAllowAgainNextDay() {
            serviceAt("2026-10-30T10:00").relocate(31L, MOVE);
            serviceAt("2026-10-30T18:00").relocate(31L, MOVE);

            serviceAt("2026-10-31T09:00").relocate(31L, MOVE);

            assertThat(address.changesOn(DAY.plusDays(1))).isEqualTo(1);
        }

        @Test
        @DisplayName("Gün Istanbul saatine göre döner, UTC'ye göre değil")
        void shouldRollOverAtIstanbulMidnight() {
            serviceAt("2026-10-30T10:00").relocate(31L, MOVE);
            serviceAt("2026-10-30T11:00").relocate(31L, MOVE);

            // Istanbul 00:30 = UTC 21:30 (önceki gün). UTC'ye göre hâlâ 30 Ekim olurdu.
            serviceAt("2026-10-31T00:30").relocate(31L, MOVE);

            assertThat(address.changesOn(DAY.plusDays(1))).isEqualTo(1);
        }

        @Test
        @DisplayName("Reddedilen konum hakkı tüketmez")
        void shouldNotConsumeChangeWhenPointRejected() {
            when(validator.validate(anyDouble(), anyDouble(), eq(31L))).thenReturn(FAR_FROM_ROAD);

            assertThatThrownBy(() -> serviceAt("2026-10-30T10:00").relocate(31L, MOVE))
                    .isInstanceOf(ApiException.class);

            assertThat(address.changesOn(DAY)).isZero();
        }

        @Test
        @DisplayName("Taşıma doğrulamayı sıfırlar")
        void shouldResetVerification() {
            address.verify(1L);

            Address moved = serviceAt("2026-10-30T10:00").relocate(31L, MOVE);

            assertThat(moved.getVerificationStatus()).isEqualTo(VerificationStatus.DOGRULAMA_BEKLIYOR);
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("Geçerli nokta doğrulama kuyruğuna kaydedilir")
        void shouldSavePendingAddress() {
            Address saved = serviceAt("2026-10-30T10:00").create(12L, MOVE);

            assertThat(saved.getVerificationStatus()).isEqualTo(VerificationStatus.DOGRULAMA_BEKLIYOR);
            assertThat(saved.getSnappedNodeId()).isEqualTo(48219L);
        }

        @Test
        @DisplayName("Sınır dışı nokta OUTSIDE_SERVICE_AREA ile reddedilir, kaydedilmez")
        void shouldRejectOutsidePoint() {
            when(validator.validate(anyDouble(), anyDouble(), any())).thenReturn(OUTSIDE);

            assertThatThrownBy(() -> serviceAt("2026-10-30T10:00").create(12L, MOVE))
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ErrorCode.OUTSIDE_SERVICE_AREA);
            verify(addresses, never()).save(any());
        }

        @Test
        @DisplayName("Yoldan uzak nokta TOO_FAR_FROM_ROAD ile reddedilir")
        void shouldRejectFarFromRoad() {
            when(validator.validate(anyDouble(), anyDouble(), any())).thenReturn(FAR_FROM_ROAD);

            assertThatThrownBy(() -> serviceAt("2026-10-30T10:00").create(12L, MOVE))
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ErrorCode.TOO_FAR_FROM_ROAD);
        }
    }

    @Nested
    @DisplayName("verify / reject")
    class VerifyReject {

        @Test
        @DisplayName("Zaten onaylı adresi tekrar onaylamak hata değildir ve onaylayanı değiştirmez")
        void verifyIsIdempotent() {
            AddressService service = serviceAt("2026-10-30T10:00");
            service.verify(31L, 1L);

            service.verify(31L, 2L);

            assertThat(address.getVerifiedBy()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Bilinmeyen adres NOT_FOUND")
        void unknownAddressIsNotFound() {
            when(addresses.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> serviceAt("2026-10-30T10:00").reject(99L, 1L, "x"))
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }
    }
}
