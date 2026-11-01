package com.routewise.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.routewise.auth.AuthenticatedUser;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.Algorithm;
import com.routewise.optimization.OptimizationRun;
import com.routewise.optimization.OptimizationRunRepository;
import com.routewise.optimization.PlannedRoute;
import com.routewise.optimization.PlannedRouteRepository;
import com.routewise.optimization.RouteStop;
import com.routewise.optimization.RouteStopRepository;
import com.routewise.optimization.RunDtos.RouteDetail;
import com.routewise.optimization.RunDtos.RunDetail;
import com.routewise.optimization.RunDtos.StopDetail;
import com.routewise.optimization.RunService;
import com.routewise.plan.DeliveryPlan;
import com.routewise.plan.DeliveryPlanRepository;
import com.routewise.plan.PlanStatus;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.shipment.ShipmentStatus;
import com.routewise.user.Role;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CourierServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T07:30:00Z");
    private static final long ME = 1L;

    private final CourierRepository couriers = mock(CourierRepository.class);
    private final PlannedRouteRepository routes = mock(PlannedRouteRepository.class);
    private final RouteStopRepository stops = mock(RouteStopRepository.class);
    private final OptimizationRunRepository runs = mock(OptimizationRunRepository.class);
    private final DeliveryPlanRepository plans = mock(DeliveryPlanRepository.class);
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final RunService runService = mock(RunService.class);
    private final CourierService service = new CourierService(couriers, routes, stops, runs, plans, shipments,
            runService, Clock.fixed(NOW, ZoneId.of("Europe/Istanbul")));

    private PlannedRoute route;
    private RouteStop first;
    private RouteStop second;
    private Shipment s1;
    private Shipment s2;
    private DeliveryPlan plan;

    private static <T> T withId(T e, long id) {
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @BeforeEach
    void setUp() {
        plan = withId(new DeliveryPlan(1L, LocalDate.of(2026, 11, 1), 1L), 4L);
        plan.markDispatched();
        OptimizationRun run = withId(new OptimizationRun(4L, Algorithm.ROUTEWISE, true, false,
                BigDecimal.TEN, 1, (short) 1, 1), 50L);
        route = withId(new PlannedRoute(50L, 3L, BigDecimal.TEN, 1, BigDecimal.ONE), 7L);
        route.assignCourier(ME);
        first = withId(new RouteStop(7L, 61L, 1, 1L, BigDecimal.ONE, 1), 101L);
        second = withId(new RouteStop(7L, 62L, 2, 2L, BigDecimal.ONE, 1), 102L);
        s1 = withId(new Shipment("BND-2026-00061", 12L, 31L, BigDecimal.ONE, (short) 0, 5), 61L);
        s2 = withId(new Shipment("BND-2026-00062", 12L, 32L, BigDecimal.ONE, (short) 0, 5), 62L);
        s1.markPlanned();
        s2.markPlanned();

        when(stops.findById(101L)).thenReturn(Optional.of(first));
        when(stops.findById(102L)).thenReturn(Optional.of(second));
        when(routes.lockById(7L)).thenReturn(Optional.of(route));
        when(routes.findById(7L)).thenReturn(Optional.of(route));
        when(routes.findByRunIdOrderByIdAsc(50L)).thenReturn(List.of(route));
        when(stops.findByRouteIdOrderBySequenceNoAsc(7L)).thenReturn(List.of(first, second));
        when(shipments.findAllById(anyList())).thenReturn(List.of(s1, s2));
        when(runs.findById(50L)).thenReturn(Optional.of(run));
        when(plans.lockById(4L)).thenReturn(Optional.of(plan));
        when(runService.route(anyLong(), eq(false))).thenAnswer(inv -> detail());
    }

    /** RunService'in döndüreceği durak listesi — kapatılan durağın güncel hâlini taşır. */
    private RunDetail detail() {
        List<StopDetail> list = List.of(stop(first), stop(second));
        return new RunDetail(null, null, List.of(new RouteDetail(7L, 3L, "10 ABC 003", BigDecimal.TEN, 1,
                BigDecimal.ONE, null, null, list, null)), false);
    }

    private static StopDetail stop(RouteStop s) {
        return new StopDetail(s.getId(), s.getSequenceNo(), s.getShipmentId(), null, null, null, null,
                BigDecimal.ONE, 1, null, s.getStatus(), null, null, s.getActualArrival(), s.getFailureReason());
    }

    @Nested
    @DisplayName("Durak kapatma")
    class Close {

        @Test
        @DisplayName("İlk durak rotayı yola çıkarır: açık durakların kargoları YOLDA olur")
        void firstCloseStartsRoute() {
            service.deliver(ME, 101L);

            assertThat(route.getStatus()).isEqualTo("YOLDA");
            assertThat(route.getStartedAt()).isEqualTo(NOW);
            assertThat(s1.getStatus()).isEqualTo(ShipmentStatus.TESLIM_EDILDI);
            assertThat(s2.getStatus()).isEqualTo(ShipmentStatus.YOLDA);
            assertThat(first.getActualArrival()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Ulaşılamadı: sebep saklanır, kargo başka gün planlanmak üzere havuza döner")
        void failReturnsShipmentToPool() {
            StopDetail d = service.fail(ME, 101L, "Adreste kimse yok");

            assertThat(first.getStatus()).isEqualTo(RouteStop.FAILED);
            assertThat(d.failureReason()).isEqualTo("Adreste kimse yok");
            assertThat(s1.getStatus()).isEqualTo(ShipmentStatus.BEKLEMEDE);
        }

        @Test
        @DisplayName("Son durak rotayı, son rota planı tamamlar")
        void lastCloseCompletesRouteAndPlan() {
            service.deliver(ME, 101L);
            service.fail(ME, 102L, "Adres bulunamadı");

            assertThat(route.getStatus()).isEqualTo("TAMAMLANDI");
            assertThat(route.getCompletedAt()).isEqualTo(NOW);
            assertThat(plan.getStatus()).isEqualTo(PlanStatus.TAMAMLANDI);
        }

        @Test
        @DisplayName("Kapalı durak tekrar kapatılamaz — çift dokunuş teslimatı iki kez yazmaz")
        void closedStopIs409() {
            service.deliver(ME, 101L);

            assertThatThrownBy(() -> service.deliver(ME, 101L))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.STOP_ALREADY_CLOSED);
        }

        @Test
        @DisplayName("Başka kuryenin durağı 404 — var olduğu sızdırılmaz")
        void otherCouriersStopIs404() {
            assertThatThrownBy(() -> service.deliver(99L, 101L))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(first.isOpen()).isTrue();
        }
    }

    @Nested
    @DisplayName("Okuma")
    class Read {

        @Test
        @DisplayName("Sevk edilmiş rota yoksa 404 — ekran 'bugün rotan yok' der")
        void noDispatchedRoute() {
            when(routes.findDispatchedFor(ME, LocalDate.of(2026, 11, 1))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.route(ME, LocalDate.of(2026, 11, 1)))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("Kurye başkasının durağını okuyamaz; firma (courierId=null) okuyabilir")
        void stopOwnership() {
            assertThatThrownBy(() -> service.stop(99L, 101L))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(service.stop(null, 101L).id()).isEqualTo(101L);
            assertThat(service.stop(ME, 101L).id()).isEqualTo(101L);
        }

        @Test
        @DisplayName("Bugün İstanbul saatine göre")
        void todayIsIstanbul() {
            assertThat(service.today()).isEqualTo(LocalDate.of(2026, 11, 1));
        }
    }

    @Nested
    @DisplayName("Controller rol kuralı")
    class Roles {

        @Test
        @DisplayName("Firma durak kapatamaz: 403")
        void companyCannotClose() {
            var controller = new CourierController(service);
            var company = new AuthenticatedUser(1L, "firma@routewise.local", Role.COMPANY);

            assertThatThrownBy(() -> controller.deliver(101L, company))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(first.isOpen()).isTrue();
        }

        @Test
        @DisplayName("Firma kuryesiz rota isteyemez: 400")
        void companyMustNameCourier() {
            var controller = new CourierController(service);
            var company = new AuthenticatedUser(1L, "firma@routewise.local", Role.COMPANY);

            assertThatThrownBy(() -> controller.route(null, null, company))
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
    }
}
