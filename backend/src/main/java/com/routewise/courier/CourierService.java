package com.routewise.courier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.OptimizationRun;
import com.routewise.optimization.OptimizationRunRepository;
import com.routewise.optimization.PlannedRoute;
import com.routewise.optimization.PlannedRouteRepository;
import com.routewise.optimization.RouteStop;
import com.routewise.optimization.RouteStopRepository;
import com.routewise.optimization.RunDtos.RunDetail;
import com.routewise.optimization.RunDtos.StopDetail;
import com.routewise.optimization.RunService;
import com.routewise.plan.DeliveryPlan;
import com.routewise.plan.DeliveryPlanRepository;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The courier's day (K2–K4): today's route, a stop, and closing stops.
 *
 * <p>"Own" is enforced here: a stop on another courier's route answers 404, like
 * a stop that does not exist — a 403 would confirm the id is real.
 *
 * <p>Closing a stop drives the lifecycle: the first closed stop puts the route
 * on the road (its shipments become YOLDA, so their address locks), the last
 * one completes the route, and the last route completes the plan. A failed
 * stop's shipment goes back to the pool to be planned on another day.
 * The courier's position is "the last closed stop" — no live GPS (architecture §4).
 */
@Service
public class CourierService {

    private static final Logger log = LoggerFactory.getLogger(CourierService.class);

    private final CourierRepository couriers;
    private final PlannedRouteRepository routes;
    private final RouteStopRepository stops;
    private final OptimizationRunRepository runs;
    private final DeliveryPlanRepository plans;
    private final ShipmentRepository shipments;
    private final RunService runService;
    private final Clock clock;

    public CourierService(CourierRepository couriers, PlannedRouteRepository routes,
                          RouteStopRepository stops, OptimizationRunRepository runs,
                          DeliveryPlanRepository plans, ShipmentRepository shipments,
                          RunService runService, Clock clock) {
        this.couriers = couriers;
        this.routes = routes;
        this.stops = stops;
        this.runs = runs;
        this.plans = plans;
        this.shipments = shipments;
        this.runService = runService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Long courierIdOf(Long userId) {
        return couriers.findByUserId(userId).map(Courier::getId)
                .orElseThrow(() -> ApiException.notFound("Kurye profili"));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The day's dispatched route with geometry; none dispatched yet is a 404 the screen explains. */
    public RunDetail route(Long courierId, LocalDate date) {
        PlannedRoute route = routes.findDispatchedFor(courierId, date)
                .orElseThrow(() -> ApiException.notFound("Bu gün için sevk edilmiş rota"));
        return runService.route(route.getId());
    }

    /** @param courierId null for a company caller, who may read any stop */
    public StopDetail stop(Long courierId, Long stopId) {
        RouteStop stop = stops.findById(stopId).orElseThrow(() -> ApiException.notFound("Durak"));
        PlannedRoute route = routes.findById(stop.getRouteId()).orElseThrow(() -> ApiException.notFound("Durak"));
        if (courierId != null && !courierId.equals(route.getCourierId())) {
            throw ApiException.notFound("Durak");
        }
        return runService.route(route.getId(), false).routes().get(0).stops().stream()
                .filter(s -> s.id().equals(stopId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Durak"));
    }

    @Transactional
    public StopDetail deliver(Long courierId, Long stopId) {
        Closed c = close(courierId, stopId, (stop, now) -> stop.deliver(now), Shipment::markDelivered);
        log.info("durak {} teslim edildi (kurye {}, rota {})", stopId, courierId, c.routeId());
        return detailAfterClose(c);
    }

    @Transactional
    public StopDetail fail(Long courierId, Long stopId, String reason) {
        Closed c = close(courierId, stopId, (stop, now) -> stop.fail(now, reason), Shipment::returnToPool);
        log.info("durak {} ulasilamadi (kurye {}, rota {}): {}", stopId, courierId, c.routeId(), reason);
        return detailAfterClose(c);
    }

    private record Closed(Long routeId, Long stopId) {}

    @FunctionalInterface
    private interface StopAction {
        void apply(RouteStop stop, Instant now);
    }

    private Closed close(Long courierId, Long stopId, StopAction onStop,
                         Consumer<Shipment> onShipment) {
        RouteStop stop = stops.findById(stopId).orElseThrow(() -> ApiException.notFound("Durak"));
        PlannedRoute route = routes.lockById(stop.getRouteId()).orElseThrow(() -> ApiException.notFound("Durak"));
        if (!courierId.equals(route.getCourierId())) {
            throw ApiException.notFound("Durak");
        }
        if (!stop.isOpen()) {
            throw new ApiException(ErrorCode.STOP_ALREADY_CLOSED, "Bu durak zaten kapatıldı.");
        }

        Instant now = clock.instant();
        List<RouteStop> all = stops.findByRouteIdOrderBySequenceNoAsc(route.getId());
        Map<Long, Shipment> byId = shipments.findAllById(all.stream().map(RouteStop::getShipmentId).toList())
                .stream().collect(Collectors.toMap(Shipment::getId, Function.identity()));

        if (route.startIfPlanned(now)) {
            // Rota yola çıktı: henüz kapanmamış durakların kargoları artık yolda.
            all.stream().filter(RouteStop::isOpen)
                    .forEach(s -> byId.get(s.getShipmentId()).markOnRoute());
        }
        onStop.apply(stop, now);
        onShipment.accept(byId.get(stop.getShipmentId()));

        if (all.stream().noneMatch(RouteStop::isOpen)) {
            route.complete(now);
            completePlanIfDone(route);
        }
        return new Closed(route.getId(), stop.getId());
    }

    private void completePlanIfDone(PlannedRoute route) {
        OptimizationRun run = runs.findById(route.getRunId()).orElseThrow();
        boolean allDone = routes.findByRunIdOrderByIdAsc(run.getId()).stream()
                .allMatch(r -> "TAMAMLANDI".equals(r.getStatus()));
        if (allDone) {
            DeliveryPlan plan = plans.lockById(run.getPlanId()).orElseThrow();
            plan.markCompleted();
            log.info("plan {} tamamlandi", plan.getId());
        }
    }

    private StopDetail detailAfterClose(Closed c) {
        // Yanıt yazılan durumu göstermeli: değişiklikler okunmadan önce veritabanına iner.
        stops.flush();
        return runService.route(c.routeId(), false).routes().get(0).stops().stream()
                .filter(s -> s.id().equals(c.stopId())).findFirst().orElseThrow();
    }
}
