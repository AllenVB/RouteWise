package com.routewise.optimization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.routewise.address.Address;
import com.routewise.address.AddressRepository;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.common.geo.Points;
import com.routewise.depot.Depot;
import com.routewise.depot.DepotService;
import com.routewise.optimization.RunDtos.DepotPoint;
import com.routewise.optimization.RunDtos.LineString;
import com.routewise.optimization.RunDtos.RouteDetail;
import com.routewise.optimization.RunDtos.RunDetail;
import com.routewise.optimization.RunDtos.RunSummary;
import com.routewise.optimization.RunDtos.StopDetail;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.GeometryResult;
import com.routewise.optimization.client.OptimizerDtos.LegGeometry;
import com.routewise.plan.DeliveryPlanRepository;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.vehicle.Vehicle;
import com.routewise.vehicle.VehicleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of runs: summaries, and the detail the planning map draws. */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final OptimizationRunRepository runs;
    private final PlannedRouteRepository routes;
    private final RouteStopRepository stops;
    private final DeliveryPlanRepository plans;
    private final ShipmentRepository shipments;
    private final AddressRepository addresses;
    private final VehicleRepository vehicles;
    private final DepotService depots;
    private final OptimizerClient optimizer;

    public RunService(OptimizationRunRepository runs, PlannedRouteRepository routes,
                      RouteStopRepository stops, DeliveryPlanRepository plans,
                      ShipmentRepository shipments, AddressRepository addresses,
                      VehicleRepository vehicles, DepotService depots, OptimizerClient optimizer) {
        this.runs = runs;
        this.routes = routes;
        this.stops = stops;
        this.plans = plans;
        this.shipments = shipments;
        this.addresses = addresses;
        this.vehicles = vehicles;
        this.depots = depots;
        this.optimizer = optimizer;
    }

    @Transactional(readOnly = true)
    public List<RunSummary> forPlan(Long planId) {
        if (!plans.existsById(planId)) {
            throw ApiException.notFound("Plan");
        }
        return runs.findByPlanIdOrderByIdDesc(planId).stream().map(RunService::summary).toList();
    }

    /**
     * Run detail with road geometry.
     *
     * <p>Not transactional as a whole: geometry is an HTTP call to the optimizer
     * and must not hold a connection. Stored stops and metrics are the truth;
     * if the optimizer is down they are still returned, with
     * {@code geometryAvailable=false} instead of failing the screen.
     */
    public RunDetail detail(Long runId) {
        OptimizationRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Koşu"));
        return build(run, routes.findByRunIdOrderByIdAsc(runId));
    }

    /**
     * One route of a run (F7), in the same shape as the run detail with a single
     * route — the screen reuses one renderer for both.
     */
    public RunDetail route(Long routeId) {
        PlannedRoute route = routes.findById(routeId).orElseThrow(() -> ApiException.notFound("Rota"));
        OptimizationRun run = runs.findById(route.getRunId()).orElseThrow(() -> ApiException.notFound("Koşu"));
        return build(run, List.of(route));
    }

    private RunDetail build(OptimizationRun run, List<PlannedRoute> runRoutes) {
        Long runId = run.getId();
        Depot depot = depots.primary();

        Map<Long, List<RouteStop>> stopsByRoute = stops
                .findByRouteIdInOrderByRouteIdAscSequenceNoAsc(
                        runRoutes.stream().map(PlannedRoute::getId).toList())
                .stream().collect(Collectors.groupingBy(RouteStop::getRouteId));

        List<RouteStop> allStops = stopsByRoute.values().stream().flatMap(List::stream).toList();
        Map<Long, Shipment> shipmentById = byId(shipments.findAllById(
                allStops.stream().map(RouteStop::getShipmentId).distinct().toList()), Shipment::getId);
        Map<Long, Address> addressById = byId(addresses.findAllById(
                shipmentById.values().stream().map(Shipment::getAddressId).distinct().toList()),
                Address::getId);
        Map<Long, Vehicle> vehicleById = byId(vehicles.findAllById(
                runRoutes.stream().map(PlannedRoute::getVehicleId).distinct().toList()), Vehicle::getId);

        boolean geometryAvailable = true;
        List<RouteDetail> details = new ArrayList<>();
        for (PlannedRoute r : runRoutes) {
            List<RouteStop> rs = stopsByRoute.getOrDefault(r.getId(), List.of());
            LineString geometry = null;
            if (geometryAvailable && !rs.isEmpty()) {
                try {
                    geometry = geometry(depot.getSnappedNodeId(), rs);
                } catch (ApiException e) {
                    if (e.code() != ErrorCode.OPTIMIZER_UNAVAILABLE && e.code() != ErrorCode.OPTIMIZER_TIMEOUT) {
                        throw e;
                    }
                    log.warn("kosu {} geometrisi alinamadi: {}", runId, e.getMessage());
                    geometryAvailable = false;
                }
            }
            Vehicle v = vehicleById.get(r.getVehicleId());
            details.add(new RouteDetail(r.getId(), r.getVehicleId(), v == null ? null : v.getPlate(),
                    r.getTotalDistanceM(), r.getTotalDurationS(), r.getTotalLoadKg(),
                    r.getTotalFuelL(), r.getTotalCostTry(),
                    rs.stream().map(s -> stopDetail(s, shipmentById, addressById)).toList(),
                    geometry));
        }

        return new RunDetail(summary(run),
                new DepotPoint(depot.getName(), Points.latOf(depot.getLocation()),
                        Points.lonOf(depot.getLocation())),
                details, geometryAvailable);
    }

    /** Uses the nodes stored with the run, so a moved address does not redraw an old run. */
    private LineString geometry(Long depotNode, List<RouteStop> rs) {
        List<Long> sequence = new ArrayList<>();
        sequence.add(depotNode);
        rs.forEach(s -> sequence.add(s.getNodeId()));
        sequence.add(depotNode);

        GeometryResult g = optimizer.routeGeometry(sequence);
        List<double[]> line = new ArrayList<>();
        for (LegGeometry leg : g.legs()) {
            List<double[]> c = leg.coordinates();
            // Ardışık bacaklar ortak düğümde birleşir; aynı noktayı iki kez koyma.
            line.addAll(line.isEmpty() ? c : c.subList(Math.min(1, c.size()), c.size()));
        }
        return LineString.of(line);
    }

    private static StopDetail stopDetail(RouteStop s, Map<Long, Shipment> shipments,
                                         Map<Long, Address> addresses) {
        Shipment sh = shipments.get(s.getShipmentId());
        Address a = sh == null ? null : addresses.get(sh.getAddressId());
        return new StopDetail(s.getId(), s.getSequenceNo(), s.getShipmentId(),
                sh == null ? null : sh.getOrderNumber(),
                a == null ? null : a.getAddressText(),
                a == null ? null : Points.latOf(a.getLocation()),
                a == null ? null : Points.lonOf(a.getLocation()),
                s.getDistanceFromPrevM(), s.getDurationFromPrevS(), s.getEta(), s.getStatus());
    }

    static RunSummary summary(OptimizationRun r) {
        return new RunSummary(r.getId(), r.getPlanId(), r.getAlgorithm(), r.isApplied(), r.isBaseline(),
                r.getTotalDistanceM(), r.getTotalDurationS(), r.getTotalFuelL(), r.getTotalCostTry(),
                r.getVehicleCount(), r.getComputationTimeMs(), r.getRandomSeed(), r.getCreatedAt());
    }

    private static <T> Map<Long, T> byId(List<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
