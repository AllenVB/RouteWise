package com.routewise.optimization;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.routewise.address.Address;
import com.routewise.address.AddressRepository;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.cost.CostParameters;
import com.routewise.cost.CostParametersRepository;
import com.routewise.depot.Depot;
import com.routewise.depot.DepotService;
import com.routewise.optimization.RunDtos.BaselineSummary;
import com.routewise.optimization.RunDtos.ExcludedShipment;
import com.routewise.optimization.RunDtos.OptimizeResponse;
import com.routewise.optimization.RunDtos.RouteSummary;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.CostParamsIn;
import com.routewise.optimization.client.OptimizerDtos.MatrixIn;
import com.routewise.optimization.client.OptimizerDtos.OptimizeRequest;
import com.routewise.optimization.client.OptimizerDtos.OptimizeResult;
import com.routewise.optimization.client.OptimizerDtos.RouteOut;
import com.routewise.optimization.client.OptimizerDtos.SolutionOut;
import com.routewise.optimization.client.OptimizerDtos.StopIn;
import com.routewise.optimization.client.OptimizerDtos.StopOut;
import com.routewise.optimization.client.OptimizerDtos.UnassignedOut;
import com.routewise.optimization.client.OptimizerDtos.VehicleIn;
import com.routewise.optimization.client.OptimizerDtos.WarmRouteIn;
import com.routewise.plan.DeliveryPlan;
import com.routewise.plan.DeliveryPlanRepository;
import com.routewise.routing.matrix.DistanceMatrix;
import com.routewise.routing.matrix.DistanceMatrixProvider;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.vehicle.Vehicle;
import com.routewise.vehicle.VehicleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs an optimization over a plan and stores it (architecture §12, steps 2–6).
 *
 * <p>Deliberately <b>not</b> one transaction: the optimizer call can take seconds
 * and must not hold a database connection and row locks meanwhile. Reads happen
 * first, the HTTP call in between, and the write in its own short transaction
 * that re-checks the plan under a row lock.
 *
 * <p>The whole active fleet is planned (CVRP) with the cost parameters in force
 * on the plan's date. Dispatch to couriers is a separate step.
 */
@Service
public class OptimizationService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationService.class);

    /** The matrix holds at most 300 nodes; one of them is the depot. */
    static final int MAX_STOPS = 299;

    static final String ADDRESS_NOT_VERIFIED = "ADDRESS_NOT_VERIFIED";
    static final String NO_ROAD_NODE = "NO_ROAD_NODE";

    private final DeliveryPlanRepository plans;
    private final DepotService depots;
    private final VehicleRepository vehicles;
    private final ShipmentRepository shipments;
    private final AddressRepository addresses;
    private final CostParametersRepository costs;
    private final DistanceMatrixProvider matrices;
    private final OptimizerClient optimizer;
    private final OptimizationRunRepository runs;
    private final PlannedRouteRepository routes;
    private final RouteStopRepository stops;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OptimizationService(DeliveryPlanRepository plans, DepotService depots,
                               VehicleRepository vehicles, ShipmentRepository shipments,
                               AddressRepository addresses, CostParametersRepository costs,
                               DistanceMatrixProvider matrices, OptimizerClient optimizer,
                               OptimizationRunRepository runs, PlannedRouteRepository routes,
                               RouteStopRepository stops, TransactionTemplate tx, Clock clock) {
        this.plans = plans;
        this.depots = depots;
        this.vehicles = vehicles;
        this.shipments = shipments;
        this.addresses = addresses;
        this.costs = costs;
        this.matrices = matrices;
        this.optimizer = optimizer;
        this.runs = runs;
        this.routes = routes;
        this.stops = stops;
        this.tx = tx;
        this.clock = clock;
    }

    public OptimizeResponse optimize(Long planId, RunDtos.OptimizeRequest req) {
        RunDtos.OptimizeRequest r = req == null ? new RunDtos.OptimizeRequest(null, null, null, null) : req;
        Algorithm algorithm = r.algorithm() == null ? Algorithm.ROUTEWISE : r.algorithm();
        if (!algorithm.isAvailable()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    algorithm + " henüz kullanılamıyor (karşılaştırma modülüyle gelecek).");
        }
        if (!algorithm.isSeeded() && (r.randomSeed() != null || r.warmStartFromRunId() != null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Tohum ve sıcak başlangıç yalnızca ROUTEWISE ile kullanılabilir.");
        }

        DeliveryPlan plan = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Plan"));
        requireOptimizable(plan);
        Depot depot = depots.primary();
        List<Vehicle> fleet = fleet(depot, r.vehicleId());
        CostParameters price = costs.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(plan.getPlanDate())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "Plan tarihi için tanımlı maliyet parametresi yok."));

        Candidates c = candidates(plan);
        if (c.routable().isEmpty()) {
            throw new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, c.excluded().isEmpty()
                    ? "Planlanacak kargo yok."
                    : "Planlanacak kargoların hiçbirinin adresi doğrulanmamış.");
        }
        if (c.routable().size() > MAX_STOPS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Tek planda en fazla " + MAX_STOPS + " teslimat optimize edilebilir; "
                            + c.routable().size() + " var.");
        }
        List<WarmRouteIn> warm = r.warmStartFromRunId() == null ? null : warmStart(planId, r.warmStartFromRunId());

        List<Long> nodes = new ArrayList<>();
        nodes.add(depot.getSnappedNodeId());
        c.routable().forEach(s -> nodes.add(c.nodeOf().get(s.getId())));
        DistanceMatrix matrix = matrices.forNodes(nodes);

        OptimizeResult result = optimizer.optimize(new OptimizeRequest(
                algorithm.name(),
                depot.getSnappedNodeId(),
                c.routable().stream().map(s -> new StopIn(
                        s.getId(), c.nodeOf().get(s.getId()), s.getWeightKg().doubleValue(),
                        s.getServiceTimeMin() * 60.0, s.getPriority())).toList(),
                fleet.stream().map(v -> new VehicleIn(v.getId(), v.getCapacityKg().doubleValue(),
                        v.getFuelLPer100km().doubleValue(), v.getFixedCostPerDay().doubleValue())).toList(),
                new MatrixIn(matrix.nodeIds(), matrix.distanceM(), matrix.durationS()),
                new CostParamsIn(price.getFuelPricePerL().doubleValue(),
                        price.getDriverCostPerHour().doubleValue(),
                        price.getPriorityPenalty().doubleValue()),
                r.randomSeed(),
                warm,
                depot.getMaxRouteDurationMin() * 60.0));

        // Rotalar depo açılışında, depo saat diliminde (İstanbul) yola çıkar.
        Instant departure = ZonedDateTime.of(plan.getPlanDate(), depot.getOpeningTime(), clock.getZone()).toInstant();
        OptimizeResponse response = tx.execute(status -> persist(planId, algorithm, result, c, departure));
        log.info("plan {} optimize: {} {} TL / {} m, naive {} TL, {} arac, {} kargo disarida, tohum {}",
                planId, algorithm, response.totalCostTry(), response.totalDistanceM(),
                response.baseline() == null ? "-" : response.baseline().totalCostTry(), response.vehicleCount(),
                response.excluded().size(), response.randomSeed());
        return response;
    }

    private static void requireOptimizable(DeliveryPlan plan) {
        if (!plan.getStatus().isOptimizable()) {
            throw new ApiException(ErrorCode.PLAN_ALREADY_DISPATCHED,
                    "Sevk edilmiş plan yeniden optimize edilemez.");
        }
    }

    /** The whole active fleet, or a single requested vehicle (single-vehicle scenario). */
    private List<Vehicle> fleet(Depot depot, Long vehicleId) {
        List<Vehicle> active = vehicles.findByDepotIdAndActiveTrueOrderByIdAsc(depot.getId());
        if (vehicleId != null) {
            return List.of(active.stream().filter(v -> v.getId().equals(vehicleId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Aktif araç")));
        }
        if (active.isEmpty()) {
            throw new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, "Depoda aktif araç yok.");
        }
        return active;
    }

    /**
     * The previous run's routes, in visiting order.
     *
     * <p>Shipments no longer plannable are simply ignored by the optimizer, so a
     * stale run is safe to continue from; a run of another plan is not.
     */
    private List<WarmRouteIn> warmStart(Long planId, Long runId) {
        OptimizationRun run = runs.findById(runId)
                .filter(x -> x.getPlanId().equals(planId))
                .orElseThrow(() -> ApiException.notFound("Bu plana ait koşu"));
        List<PlannedRoute> rs = routes.findByRunIdOrderByIdAsc(run.getId());
        Map<Long, List<RouteStop>> byRoute = stops
                .findByRouteIdInOrderByRouteIdAscSequenceNoAsc(rs.stream().map(PlannedRoute::getId).toList())
                .stream().collect(Collectors.groupingBy(RouteStop::getRouteId));
        return rs.stream().map(rt -> new WarmRouteIn(rt.getVehicleId(),
                byRoute.getOrDefault(rt.getId(), List.of()).stream().map(RouteStop::getShipmentId).toList()))
                .toList();
    }

    /**
     * Splits the day's shipments into routable and excluded.
     *
     * <p>Verification is checked <b>here</b>, at optimization time, not only when
     * the shipment was created: an address moved since then is back in the
     * queue, and routing to its old approval is exactly the error the
     * verification flow exists to prevent.
     */
    Candidates candidates(DeliveryPlan plan) {
        List<Shipment> all = shipments.findPlannableOn(plan.getPlanDate());
        Map<Long, Address> byId = addresses.findAllById(
                        all.stream().map(Shipment::getAddressId).distinct().toList()).stream()
                .collect(Collectors.toMap(Address::getId, Function.identity()));

        List<Shipment> routable = new ArrayList<>();
        List<ExcludedShipment> excluded = new ArrayList<>();
        Map<Long, Long> nodeOf = new HashMap<>();
        for (Shipment s : all) {
            Address a = byId.get(s.getAddressId());
            if (a == null || !a.getVerificationStatus().isUsableForRouting()) {
                excluded.add(new ExcludedShipment(s.getId(), s.getOrderNumber(), ADDRESS_NOT_VERIFIED));
                continue;
            }
            if (a.getSnappedNodeId() == null) {
                // Doğrulama akışından geçen adres her zaman düğüm taşır; bu, akış
                // dışında yazılmış bir kaydın işaretidir. "Onaysız" demek yanlış
                // yönlendirir: adres onaylı, eksik olan yol bağlantısı.
                excluded.add(new ExcludedShipment(s.getId(), s.getOrderNumber(), NO_ROAD_NODE));
                continue;
            }
            routable.add(s);
            nodeOf.put(s.getId(), a.getSnappedNodeId());
        }
        return new Candidates(routable, excluded, nodeOf);
    }

    record Candidates(List<Shipment> routable, List<ExcludedShipment> excluded, Map<Long, Long> nodeOf) {}

    private OptimizeResponse persist(Long planId, Algorithm algorithm, OptimizeResult r, Candidates c,
                                     Instant departure) {
        // Kilit altında yeniden kontrol: optimizasyon sürerken plan sevk edilmiş olabilir.
        DeliveryPlan plan = plans.lockById(planId).orElseThrow(() -> ApiException.notFound("Plan"));
        requireOptimizable(plan);
        runs.withdrawApplied(planId);

        // NAIVE istendiyse sonuç zaten baseline'dır: aynı çözüm iki satır olmaz.
        // Baseline kurulamadıysa (sıkı çalışma günü) yalnızca optimize koşu yazılır.
        boolean naiveOnly = algorithm == Algorithm.NAIVE;
        OptimizationRun baseline = null;
        List<PlannedRoute> baselineRoutes = List.of();
        if (r.baseline() != null) {
            baseline = runs.save(toRun(planId, Algorithm.NAIVE, naiveOnly, true, r.baseline(), null));
            baselineRoutes = saveRoutes(baseline, r.baseline(), departure);
        }

        OptimizationRun applied = baseline;
        List<PlannedRoute> appliedRoutes = baselineRoutes;
        if (!naiveOnly) {
            applied = runs.save(toRun(planId, algorithm, true, false, r.result(),
                    algorithm.isSeeded() ? r.randomSeed() : null));
            appliedRoutes = saveRoutes(applied, r.result(), departure);
        }

        Map<Long, String> orderNumbers = c.routable().stream()
                .collect(Collectors.toMap(Shipment::getId, Shipment::getOrderNumber));
        List<ExcludedShipment> excluded = new ArrayList<>(c.excluded());
        for (UnassignedOut u : r.unassigned()) {
            excluded.add(new ExcludedShipment(u.stopId(), orderNumbers.get(u.stopId()), u.reason()));
        }

        Map<Long, Integer> stopCounts = r.result().routes().stream()
                .collect(Collectors.toMap(RouteOut::vehicleId, ro -> ro.stops().size()));
        return new OptimizeResponse(
                applied.getId(), applied.getAlgorithm(), applied.isApplied(),
                applied.getTotalDistanceM(), applied.getTotalDurationS(),
                applied.getTotalFuelL(), applied.getTotalCostTry(),
                applied.getVehicleCount(), applied.getComputationTimeMs(), applied.getRandomSeed(),
                money(r.result().priorityPenalty()),
                baseline == null ? null : new BaselineSummary(baseline.getId(), baseline.getAlgorithm(),
                        baseline.getTotalDistanceM(), baseline.getTotalDurationS(),
                        baseline.getTotalCostTry()),
                r.baselineError(),
                baseline == null ? null : savingsPct(baseline.getTotalCostTry(), applied.getTotalCostTry()),
                baseline == null ? null : savingsPct(baseline.getTotalDistanceM(), applied.getTotalDistanceM()),
                appliedRoutes.stream().map(rt -> new RouteSummary(rt.getId(), rt.getVehicleId(),
                        rt.getCourierId(), stopCounts.getOrDefault(rt.getVehicleId(), 0))).toList(),
                excluded);
    }

    private static OptimizationRun toRun(Long planId, Algorithm algorithm, boolean applied,
                                         boolean baseline, SolutionOut s, Long seed) {
        return new OptimizationRun(planId, algorithm, applied, baseline,
                meters(s.totalDistanceM()),
                (int) Math.round(s.totalDurationS()),
                (short) s.vehicleCount(),
                (int) Math.ceil(s.computationTimeMs()),
                BigDecimal.valueOf(s.totalFuelL()).setScale(3, RoundingMode.HALF_UP),
                money(s.totalCostTry()),
                seed);
    }

    /** Only vehicles that actually leave the depot get a route row. */
    private List<PlannedRoute> saveRoutes(OptimizationRun run, SolutionOut s, Instant departure) {
        List<PlannedRoute> saved = new ArrayList<>();
        for (RouteOut ro : s.routes()) {
            if (ro.stops().isEmpty()) {
                continue;
            }
            PlannedRoute route = routes.save(new PlannedRoute(run.getId(), ro.vehicleId(),
                    meters(ro.totalDistanceM()), (int) Math.round(ro.totalDurationS()),
                    BigDecimal.valueOf(ro.totalLoadKg()).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(ro.fuelL()).setScale(3, RoundingMode.HALF_UP),
                    money(ro.costTry())));
            List<RouteStop> rows = new ArrayList<>();
            for (StopOut so : ro.stops()) {
                rows.add(new RouteStop(route.getId(), so.stopId(), so.sequenceNo(), so.nodeId(),
                        meters(so.distanceFromPrevM()), (int) Math.round(so.durationFromPrevS()),
                        departure.plusSeconds(Math.round(so.arrivalS()))));
            }
            stops.saveAll(rows);
            saved.add(route);
        }
        return saved;
    }

    static BigDecimal savingsPct(BigDecimal baseline, BigDecimal optimized) {
        if (baseline == null || optimized == null || baseline.signum() == 0) {
            return null;
        }
        return baseline.subtract(optimized)
                .multiply(BigDecimal.valueOf(100))
                .divide(baseline, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal meters(double m) {
        return BigDecimal.valueOf(m).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(Double tl) {
        return tl == null ? null : BigDecimal.valueOf(tl).setScale(2, RoundingMode.HALF_UP);
    }
}
