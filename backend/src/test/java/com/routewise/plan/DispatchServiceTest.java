package com.routewise.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.courier.Courier;
import com.routewise.courier.CourierRepository;
import com.routewise.optimization.Algorithm;
import com.routewise.optimization.OptimizationRun;
import com.routewise.optimization.OptimizationRunRepository;
import com.routewise.optimization.PlannedRoute;
import com.routewise.optimization.PlannedRouteRepository;
import com.routewise.optimization.RouteStop;
import com.routewise.optimization.RouteStopRepository;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.shipment.ShipmentStatus;
import com.routewise.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DispatchServiceTest {

    private final DeliveryPlanRepository plans = mock(DeliveryPlanRepository.class);
    private final OptimizationRunRepository runs = mock(OptimizationRunRepository.class);
    private final PlannedRouteRepository routes = mock(PlannedRouteRepository.class);
    private final RouteStopRepository stops = mock(RouteStopRepository.class);
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final CourierRepository couriers = mock(CourierRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DispatchService service =
            new DispatchService(plans, runs, routes, stops, shipments, couriers, users);

    private DeliveryPlan plan;
    private PlannedRoute r1;
    private PlannedRoute r2;
    private Shipment s1;
    private Shipment s2;

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static Courier courier(long id) {
        Courier c = mock(Courier.class);
        when(c.getId()).thenReturn(id);
        when(c.getUserId()).thenReturn(100 + id);
        return c;
    }

    @BeforeEach
    void setUp() {
        plan = withId(new DeliveryPlan(1L, LocalDate.of(2026, 10, 31), 1L), 3L);
        when(plans.lockById(3L)).thenReturn(Optional.of(plan));
        OptimizationRun run = withId(new OptimizationRun(3L, Algorithm.ROUTEWISE, true, false,
                BigDecimal.TEN, 1, (short) 2, 1), 41L);
        when(runs.findByPlanIdAndAppliedTrue(3L)).thenReturn(Optional.of(run));
        r1 = withId(new PlannedRoute(41L, 1L, BigDecimal.TEN, 1, BigDecimal.ONE), 7L);
        r2 = withId(new PlannedRoute(41L, 3L, BigDecimal.TEN, 1, BigDecimal.ONE), 8L);
        when(routes.findByRunIdOrderByIdAsc(41L)).thenReturn(List.of(r1, r2));
        when(stops.findByRouteIdInOrderByRouteIdAscSequenceNoAsc(List.of(7L, 8L))).thenReturn(List.of(
                new RouteStop(7L, 51L, 1, 1L, BigDecimal.ONE, 1),
                new RouteStop(8L, 52L, 1, 2L, BigDecimal.ONE, 1)));
        s1 = withId(new Shipment("BND-2026-00051", 12L, 31L, BigDecimal.ONE, (short) 0, 5), 51L);
        s2 = withId(new Shipment("BND-2026-00052", 12L, 32L, BigDecimal.ONE, (short) 0, 5), 52L);
        when(shipments.findAllById(anyList())).thenReturn(List.of(s1, s2));
        // Kurye mock'ları önce kurulur: thenReturn() içinde başka mock stub'lamak Mockito'yu bozar.
        List<Courier> two = List.of(courier(1), courier(2));
        when(couriers.findByStatusOrderByIdAsc(Courier.AVAILABLE)).thenReturn(two);
    }

    @Test
    @DisplayName("Her rotaya bir kurye, kargolar PLANLANDI, plan SEVK_EDILDI")
    void dispatchesEveryRoute() {
        var result = service.dispatch(3L);

        assertThat(result.status()).isEqualTo(PlanStatus.SEVK_EDILDI);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.SEVK_EDILDI);
        assertThat(r1.getCourierId()).isEqualTo(1L);
        assertThat(r2.getCourierId()).isEqualTo(2L);
        assertThat(s1.getStatus()).isEqualTo(ShipmentStatus.PLANLANDI);
        assertThat(s2.getStatus()).isEqualTo(ShipmentStatus.PLANLANDI);
        assertThat(result.routes()).extracting(DispatchService.DispatchedRoute::stopCount).containsExactly(1, 1);
    }

    @Test
    @DisplayName("Rota sayısından az kurye varsa hiçbir şey değişmez — kuryesiz rota yok")
    void notEnoughCouriers() {
        List<Courier> one = List.of(courier(1));
        when(couriers.findByStatusOrderByIdAsc(Courier.AVAILABLE)).thenReturn(one);

        assertThatThrownBy(() -> service.dispatch(3L))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_ENOUGH_COURIERS);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.TASLAK);
        assertThat(r1.getCourierId()).isNull();
        assertThat(s1.getStatus()).isEqualTo(ShipmentStatus.BEKLEMEDE);
    }

    @Test
    @DisplayName("Optimize edilmemiş plan PLAN_NOT_OPTIMIZED")
    void notOptimized() {
        when(runs.findByPlanIdAndAppliedTrue(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dispatch(3L))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_NOT_OPTIMIZED);
    }

    @Test
    @DisplayName("Tekrar sevk PLAN_ALREADY_DISPATCHED")
    void alreadyDispatched() {
        plan.markDispatched();

        assertThatThrownBy(() -> service.dispatch(3L))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_ALREADY_DISPATCHED);
    }

    @Test
    @DisplayName("Koşudan sonra yola çıkmış kargo varsa sevk durur, yeniden optimizasyon istenir")
    void staleRun() {
        ReflectionTestUtils.setField(s2, "status", ShipmentStatus.YOLDA);

        assertThatThrownBy(() -> service.dispatch(3L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("BND-2026-00052")
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_NOT_OPTIMIZED);
        assertThat(s1.getStatus()).isEqualTo(ShipmentStatus.BEKLEMEDE);
    }
}
