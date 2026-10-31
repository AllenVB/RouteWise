package com.routewise.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.courier.Courier;
import com.routewise.courier.CourierRepository;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands the applied run to couriers: each route gets a courier, its shipments
 * become PLANLANDI and the plan is locked against re-optimization.
 *
 * <p>Assignment is automatic in this phase — available couriers in id order,
 * one per route. Too few couriers is an explicit 409, never a route left
 * without a driver.
 *
 * <p>All or nothing in one transaction under the plan's row lock: a concurrent
 * optimization either finishes before (and its run is the one dispatched) or
 * finds the plan dispatched and stops.
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final DeliveryPlanRepository plans;
    private final OptimizationRunRepository runs;
    private final PlannedRouteRepository routes;
    private final RouteStopRepository stops;
    private final ShipmentRepository shipments;
    private final CourierRepository couriers;
    private final UserRepository users;

    public DispatchService(DeliveryPlanRepository plans, OptimizationRunRepository runs,
                           PlannedRouteRepository routes, RouteStopRepository stops,
                           ShipmentRepository shipments, CourierRepository couriers,
                           UserRepository users) {
        this.plans = plans;
        this.runs = runs;
        this.routes = routes;
        this.stops = stops;
        this.shipments = shipments;
        this.couriers = couriers;
        this.users = users;
    }

    public record DispatchedRoute(Long routeId, Long vehicleId, Long courierId, String courierName, int stopCount) {}

    public record DispatchResult(Long planId, PlanStatus status, Long runId, List<DispatchedRoute> routes) {}

    @Transactional
    public DispatchResult dispatch(Long planId) {
        DeliveryPlan plan = plans.lockById(planId).orElseThrow(() -> ApiException.notFound("Plan"));
        if (!plan.getStatus().isOptimizable()) {
            throw new ApiException(ErrorCode.PLAN_ALREADY_DISPATCHED, "Bu plan zaten sevk edildi.");
        }
        OptimizationRun run = runs.findByPlanIdAndAppliedTrue(planId)
                .orElseThrow(() -> new ApiException(ErrorCode.PLAN_NOT_OPTIMIZED,
                        "Sevk için önce planı optimize et."));

        List<PlannedRoute> runRoutes = routes.findByRunIdOrderByIdAsc(run.getId());
        if (runRoutes.isEmpty()) {
            throw new ApiException(ErrorCode.PLAN_NOT_OPTIMIZED, "Uygulanan koşuda rota yok.");
        }
        List<Courier> available = couriers.findByStatusOrderByIdAsc(Courier.AVAILABLE);
        if (available.size() < runRoutes.size()) {
            throw new ApiException(ErrorCode.NOT_ENOUGH_COURIERS,
                    runRoutes.size() + " rota için " + available.size() + " müsait kurye var. "
                            + "Kurye ekle ya da daha az araçla optimize et.");
        }

        Map<Long, List<RouteStop>> byRoute = stops
                .findByRouteIdInOrderByRouteIdAscSequenceNoAsc(runRoutes.stream().map(PlannedRoute::getId).toList())
                .stream().collect(Collectors.groupingBy(RouteStop::getRouteId));
        List<Long> shipmentIds = byRoute.values().stream().flatMap(List::stream).map(RouteStop::getShipmentId).toList();
        Map<Long, Shipment> byId = shipments.findAllById(shipmentIds).stream()
                .collect(Collectors.toMap(Shipment::getId, Function.identity()));

        // Optimizasyondan sonra yola çıkmış ya da teslim edilmiş bir kargo rotada
        // kalamaz; sessizce atlamak yerine yeniden optimizasyon istenir.
        List<String> stale = byId.values().stream()
                .filter(s -> s.getStatus() != ShipmentStatus.BEKLEMEDE && s.getStatus() != ShipmentStatus.ERTELENDI)
                .map(Shipment::getOrderNumber).sorted().toList();
        if (!stale.isEmpty() || byId.size() != shipmentIds.size()) {
            throw new ApiException(ErrorCode.PLAN_NOT_OPTIMIZED,
                    "Koşudan sonra kargolar değişti" + (stale.isEmpty() ? "" : " (" + String.join(", ", stale) + ")")
                            + ". Planı yeniden optimize et.");
        }

        List<DispatchedRoute> result = new ArrayList<>();
        for (int i = 0; i < runRoutes.size(); i++) {
            PlannedRoute route = runRoutes.get(i);
            Courier courier = available.get(i);
            route.assignCourier(courier.getId());
            List<RouteStop> rs = byRoute.getOrDefault(route.getId(), List.of());
            rs.forEach(s -> byId.get(s.getShipmentId()).markPlanned());
            String name = users.findById(courier.getUserId()).map(u -> u.getFullName()).orElse(null);
            result.add(new DispatchedRoute(route.getId(), route.getVehicleId(), courier.getId(), name, rs.size()));
        }
        plan.markDispatched();

        log.info("plan {} sevk edildi: kosu {}, {} rota, {} kargo", planId, run.getId(), result.size(), shipmentIds.size());
        return new DispatchResult(planId, plan.getStatus(), run.getId(), result);
    }
}
