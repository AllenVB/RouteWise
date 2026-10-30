package com.routewise.depot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.routewise.address.AddressValidationProperties;
import com.routewise.address.ServiceAreaRepository;
import com.routewise.address.ServiceAreaRepository.SnapResult;
import com.routewise.common.error.ApiException;
import com.routewise.common.geo.Points;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DepotServiceTest {

    private DepotRepository depots;
    private ServiceAreaRepository geo;
    private DepotService service;

    @BeforeEach
    void setUp() {
        depots = mock(DepotRepository.class);
        geo = mock(ServiceAreaRepository.class);
        service = new DepotService(depots, geo, new AddressValidationProperties(50.0, 5000.0, 2));
    }

    private Depot depot(Long node) {
        return new Depot(1L, "Bandırma Şube", Points.of(40.3521, 27.9767), node);
    }

    @Test
    @DisplayName("Düğümü olmayan depo, adreslerle aynı snap sorgusuyla grafa bağlanır")
    void shouldSnapDepotOnFirstUse() {
        Depot d = depot(null);
        when(depots.findFirstByOrderByIdAsc()).thenReturn(Optional.of(d));
        when(geo.nearestRoadNode(27.9767, 40.3521)).thenReturn(new SnapResult(48219L, 12.0));

        assertThat(service.primary().getSnappedNodeId()).isEqualTo(48219L);
    }

    @Test
    @DisplayName("Bağlı depo için snap tekrar sorgulanmaz")
    void shouldNotSnapTwice() {
        when(depots.findFirstByOrderByIdAsc()).thenReturn(Optional.of(depot(777L)));

        assertThat(service.primary().getSnappedNodeId()).isEqualTo(777L);
        verify(geo, never()).nearestRoadNode(anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("Eşikten uzak depo yine bağlanır — seed konumu kullanıcı düzeltemez, uyarı yeter")
    void shouldStillSnapWhenFar() {
        when(depots.findFirstByOrderByIdAsc()).thenReturn(Optional.of(depot(null)));
        when(geo.nearestRoadNode(anyDouble(), anyDouble())).thenReturn(new SnapResult(5L, 300.0));

        assertThat(service.primary().getSnappedNodeId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Yol grafı yoksa açık hata — sessizce düğümsüz rota kurulmaz")
    void shouldFailWhenGraphMissing() {
        when(depots.findFirstByOrderByIdAsc()).thenReturn(Optional.of(depot(null)));
        when(geo.nearestRoadNode(anyDouble(), anyDouble())).thenReturn(null);

        assertThatThrownBy(() -> service.primary())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("dataprep");
    }

    @Test
    @DisplayName("Depo yoksa açık hata")
    void shouldFailWhenNoDepot() {
        when(depots.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.primary()).isInstanceOf(ApiException.class);
    }
}
