package com.routewise.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.routewise.address.ServiceAreaRepository.SnapResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AddressValidatorTest {

    // Bandırma merkezi
    private static final double LAT = 40.3521;
    private static final double LON = 27.9767;

    private ServiceAreaRepository geo;
    private AddressValidator validator;

    @BeforeEach
    void setUp() {
        geo = mock(ServiceAreaRepository.class);
        validator = new AddressValidator(geo,
                new AddressValidationProperties(50.0, 5000.0, 2));

        // Varsayılan: her şey yolunda
        when(geo.containsPoint(anyDouble(), anyDouble())).thenReturn(true);
        when(geo.nearestRoadNode(anyDouble(), anyDouble())).thenReturn(new SnapResult(48219L, 7.4));
        when(geo.nearestStreetName(anyDouble(), anyDouble())).thenReturn("1204. Sokak");
        when(geo.distanceToNearestVerifiedAddress(anyDouble(), anyDouble(), any()))
                .thenReturn(1800.0);
    }

    @Test
    @DisplayName("Geçerli nokta kabul edilir ve dört katmanın sonucunu taşır")
    void shouldAcceptValidPoint() {
        var r = validator.validate(LAT, LON, null);

        assertThat(r.acceptable()).isTrue();
        assertThat(r.withinServiceArea()).isTrue();
        assertThat(r.snappedNodeId()).isEqualTo(48219L);
        assertThat(r.snapDistanceM()).isEqualTo(7.4);
        assertThat(r.reverseGeocoded()).isEqualTo("1204. Sokak");
        assertThat(r.outlier()).isFalse();
        assertThat(r.blockers()).isEmpty();
        assertThat(r.warnings()).isEmpty();
    }

    @Nested
    @DisplayName("Katman 1 — geofence")
    class Geofence {

        @Test
        @DisplayName("Sınır dışındaki nokta engellenir")
        void shouldBlockOutside() {
            when(geo.containsPoint(anyDouble(), anyDouble())).thenReturn(false);

            var r = validator.validate(41.0, 29.0, null);

            assertThat(r.acceptable()).isFalse();
            assertThat(r.withinServiceArea()).isFalse();
            assertThat(r.blockers()).anyMatch(b -> b.contains("Bandırma sınırları dışında"));
        }

        @Test
        @DisplayName("Sınır dışındayken ters geocoding yapılmaz")
        void shouldSkipGeocodingOutside() {
            when(geo.containsPoint(anyDouble(), anyDouble())).thenReturn(false);

            assertThat(validator.validate(41.0, 29.0, null).reverseGeocoded()).isNull();
        }
    }

    @Nested
    @DisplayName("Katman 2 — yola snap")
    class Snap {

        @Test
        @DisplayName("Eşiğin ötesindeki nokta engellenir")
        void shouldBlockFarFromRoad() {
            when(geo.nearestRoadNode(anyDouble(), anyDouble()))
                    .thenReturn(new SnapResult(99L, 142.0));

            var r = validator.validate(LAT, LON, null);

            assertThat(r.acceptable()).isFalse();
            assertThat(r.blockers()).anyMatch(b -> b.contains("142 m uzakta"));
        }

        @Test
        @DisplayName("Tam eşikte kabul edilir")
        void shouldAcceptAtThreshold() {
            when(geo.nearestRoadNode(anyDouble(), anyDouble()))
                    .thenReturn(new SnapResult(99L, 50.0));

            assertThat(validator.validate(LAT, LON, null).acceptable()).isTrue();
        }

        @Test
        @DisplayName("Yol ağı yoksa açık hata verir, sessizce kabul etmez")
        void shouldBlockWhenGraphMissing() {
            when(geo.nearestRoadNode(anyDouble(), anyDouble())).thenReturn(null);

            var r = validator.validate(LAT, LON, null);

            assertThat(r.acceptable()).isFalse();
            assertThat(r.blockers()).anyMatch(b -> b.contains("Yol ağı yüklenmemiş"));
        }
    }

    @Nested
    @DisplayName("Katman 4 — aykırı değer")
    class Outlier {

        @Test
        @DisplayName("Uzak nokta uyarı üretir ama engellemez")
        void shouldWarnNotBlock() {
            when(geo.distanceToNearestVerifiedAddress(anyDouble(), anyDouble(), any()))
                    .thenReturn(11000.0);

            var r = validator.validate(LAT, LON, null);

            assertThat(r.outlier()).isTrue();
            assertThat(r.acceptable()).isTrue();          // engel değil
            assertThat(r.warnings()).anyMatch(w -> w.contains("11,0 km"));
        }

        @Test
        @DisplayName("Karşılaştırılacak adres yoksa aykırı değer sayılmaz")
        void shouldNotFlagWhenNoOtherAddress() {
            when(geo.distanceToNearestVerifiedAddress(anyDouble(), anyDouble(), any()))
                    .thenReturn(null);

            var r = validator.validate(LAT, LON, null);

            assertThat(r.outlier()).isFalse();
            assertThat(r.distanceToNearestM()).isNull();
        }
    }

    @Test
    @DisplayName("Birden fazla engel bir arada raporlanır")
    void shouldReportAllBlockers() {
        when(geo.containsPoint(anyDouble(), anyDouble())).thenReturn(false);
        when(geo.nearestRoadNode(anyDouble(), anyDouble()))
                .thenReturn(new SnapResult(99L, 300.0));

        var r = validator.validate(41.0, 29.0, null);

        assertThat(r.blockers()).hasSize(2);
    }
}
