package com.routewise.comparison;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.cost.CostParameters;
import com.routewise.cost.CostParametersRepository;
import com.routewise.depot.Depot;
import com.routewise.depot.DepotService;
import com.routewise.optimization.OptimizationService;
import com.routewise.optimization.OptimizationService.Candidates;
import com.routewise.optimization.RunDtos.ExcludedShipment;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.CostParamsIn;
import com.routewise.optimization.client.OptimizerDtos.MatrixIn;
import com.routewise.optimization.client.OptimizerDtos.StopIn;
import com.routewise.optimization.client.OptimizerDtos.TourAlgorithmOut;
import com.routewise.optimization.client.OptimizerDtos.TourCompareRequest;
import com.routewise.optimization.client.OptimizerDtos.TourCompareResult;
import com.routewise.optimization.client.OptimizerDtos.UnassignedOut;
import com.routewise.optimization.client.OptimizerDtos.VehicleIn;
import com.routewise.plan.DeliveryPlan;
import com.routewise.plan.DeliveryPlanRepository;
import com.routewise.routing.matrix.DistanceMatrix;
import com.routewise.routing.matrix.DistanceMatrixProvider;
import com.routewise.shipment.Shipment;
import com.routewise.vehicle.Vehicle;
import com.routewise.vehicle.VehicleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seviye 2 — tur sırası karşılaştırması (F8, roadmap phase 6 tasks 5–7).
 *
 * <p>One vehicle, the plan's routable shipments (or a chosen subset, to build
 * small scenarios): every tour algorithm on the same input with the same
 * matrix, costs and workday as a real optimization. With at most 10 stops the
 * proven optimum (DFS_EXHAUSTIVE) is the reference. Nothing of the plan
 * changes; the comparison is measurement only.
 */
@Service
public class TourComparisonService {

    private static final Logger log = LoggerFactory.getLogger(TourComparisonService.class);

    /** Simulated annealing runs once per repeat; beyond this a comparison outlives the read timeout. */
    public static final int MAX_STOPS = 100;
    public static final int MAX_REPEATS = 10;
    static final int DEFAULT_REPEATS = 3;

    private final DeliveryPlanRepository plans;
    private final DepotService depots;
    private final VehicleRepository vehicles;
    private final CostParametersRepository costs;
    private final OptimizationService optimization;
    private final DistanceMatrixProvider matrices;
    private final OptimizerClient optimizer;
    private final ComparisonRepository comparisons;
    private final TourComparisonRunRepository runs;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TourComparisonService(DeliveryPlanRepository plans, DepotService depots, VehicleRepository vehicles,
                                 CostParametersRepository costs, OptimizationService optimization,
                                 DistanceMatrixProvider matrices, OptimizerClient optimizer,
                                 ComparisonRepository comparisons, TourComparisonRunRepository runs,
                                 ObjectMapper json, TransactionTemplate tx, Clock clock) {
        this.plans = plans;
        this.depots = depots;
        this.vehicles = vehicles;
        this.costs = costs;
        this.optimization = optimization;
        this.matrices = matrices;
        this.optimizer = optimizer;
        this.comparisons = comparisons;
        this.runs = runs;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param stopCount stops actually compared (sent minus unassigned)
     * @param excluded  the plan's shipments left out before the comparison (address not verified, …)
     */
    public record TourComparison(Long id, Comparison.Level level, Instant createdAt, Long planId, Long vehicleId,
                                 int stopCount, int repeats, List<Long> seeds, String referenceAlgorithm,
                                 boolean referenceIsOptimal, String exhaustiveSkipped,
                                 List<TourAlgorithmOut> results, List<UnassignedOut> unassigned,
                                 List<ExcludedShipment> excluded) implements ComparisonView {}

    record Params(Long vehicleId, List<Long> shipmentIds, int repeats, Long randomSeed,
                  List<ExcludedShipment> excluded) {}

    public TourComparison compare(Long planId, Long vehicleId, List<Long> shipmentIds, Integer repeats,
                                  Long randomSeed, Long userId) {
        int n = repeats == null ? DEFAULT_REPEATS : repeats;
        if (n < 1 || n > MAX_REPEATS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tekrar sayısı 1 ile " + MAX_REPEATS + " arasında olmalı.");
        }
        DeliveryPlan plan = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Plan"));
        Depot depot = depots.primary();
        Vehicle vehicle = vehicle(depot, vehicleId);
        CostParameters price = costs.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(plan.getPlanDate())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "Plan tarihi için tanımlı maliyet parametresi yok."));

        Candidates c = optimization.candidates(plan);
        List<Shipment> chosen = choose(c, shipmentIds);
        if (chosen.isEmpty()) {
            throw new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, "Karşılaştırılacak, adresi doğrulanmış kargo yok.");
        }
        if (chosen.size() > MAX_STOPS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tur karşılaştırması en fazla " + MAX_STOPS
                    + " kargoyla yapılır; " + chosen.size() + " var. shipmentIds ile bir alt küme seç.");
        }

        List<Long> nodes = new ArrayList<>();
        nodes.add(depot.getSnappedNodeId());
        chosen.forEach(s -> nodes.add(c.nodeOf().get(s.getId())));
        DistanceMatrix m = matrices.forNodes(nodes);
        TourCompareResult result = optimizer.compareTour(new TourCompareRequest(
                depot.getSnappedNodeId(),
                chosen.stream().map(s -> new StopIn(s.getId(), c.nodeOf().get(s.getId()), s.getWeightKg().doubleValue(),
                        s.getServiceTimeMin() * 60.0, s.getPriority())).toList(),
                List.of(new VehicleIn(vehicle.getId(), vehicle.getCapacityKg().doubleValue(),
                        vehicle.getFuelLPer100km().doubleValue(), vehicle.getFixedCostPerDay().doubleValue())),
                new MatrixIn(m.nodeIds(), m.distanceM(), m.durationS()),
                new CostParamsIn(price.getFuelPricePerL().doubleValue(), price.getDriverCostPerHour().doubleValue(),
                        price.getPriorityPenalty().doubleValue()),
                randomSeed,
                depot.getMaxRouteDurationMin() * 60.0,
                n));

        // Adresi doğrulanmamışlar yalnızca alt küme seçilmediyse anlamlı: seçilen kümeye zaten girmezler.
        List<ExcludedShipment> excluded = shipmentIds == null ? c.excluded() : List.of();
        Params params = new Params(vehicle.getId(), chosen.stream().map(Shipment::getId).toList(), n, randomSeed,
                excluded);
        int stopCount = chosen.size() - result.unassigned().size();
        Instant now = clock.instant();
        Comparison saved = tx.execute(status -> {
            Comparison cmp = comparisons.save(new Comparison(Comparison.Level.TOUR, planId, write(params),
                    write(result), userId, now));
            for (TourAlgorithmOut r : result.results()) {
                var s = r.solution();
                runs.save(new TourComparisonRun(cmp.getId(), r.algorithm(), stopCount, r.feasible(),
                        s == null ? null : scale(s.objective(), 2),
                        s == null || s.totalCostTry() == null ? null : scale(s.totalCostTry(), 2),
                        s == null ? null : scale(s.totalDistanceM(), 2),
                        s == null ? null : scale(s.totalDurationS(), 1),
                        scale(r.computationTimeMs(), 3),
                        r.gapPct() == null ? null : scale(r.gapPct(), 3),
                        result.referenceIsOptimal(), r.randomSeed(),
                        r.objectiveMin() == null ? null : scale(r.objectiveMin(), 2),
                        r.objectiveMax() == null ? null : scale(r.objectiveMax(), 2),
                        r.explored(), now));
            }
            return cmp;
        });
        log.info("seviye 2 karsilastirma {}: plan {}, arac {}, {} durak, {}x, referans {}{}", saved.getId(), planId,
                vehicle.getId(), stopCount, n, result.referenceAlgorithm(),
                result.referenceIsOptimal() ? " (optimum)" : "");
        return toView(saved, params, result);
    }

    TourComparison view(Comparison c) {
        return toView(c, read(c.getParams(), Params.class), read(c.getResult(), TourCompareResult.class));
    }

    private TourComparison toView(Comparison c, Params p, TourCompareResult r) {
        return new TourComparison(c.getId(), c.getLevel(), c.getCreatedAt(), c.getPlanId(), p.vehicleId(),
                p.shipmentIds().size() - r.unassigned().size(), p.repeats(), r.seeds(), r.referenceAlgorithm(),
                r.referenceIsOptimal(), r.exhaustiveSkipped(), r.results(), r.unassigned(), p.excluded());
    }

    private Vehicle vehicle(Depot depot, Long vehicleId) {
        List<Vehicle> active = vehicles.findByDepotIdAndActiveTrueOrderByIdAsc(depot.getId());
        if (vehicleId == null) {
            return active.stream().findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, "Depoda aktif araç yok."));
        }
        return active.stream().filter(v -> v.getId().equals(vehicleId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Aktif araç"));
    }

    /** The plan's routable shipments in creation order (naive's order), optionally narrowed to {@code ids}. */
    private static List<Shipment> choose(Candidates c, List<Long> ids) {
        if (ids == null) {
            return c.routable();
        }
        Set<Long> wanted = new HashSet<>(ids);
        if (wanted.size() != ids.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "shipmentIds tekrar eden kargo içeriyor.");
        }
        Set<Long> routable = new HashSet<>(c.routable().stream().map(Shipment::getId).toList());
        List<Long> unknown = ids.stream().filter(id -> !routable.contains(id)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Bu kargolar planda rotaya alınabilir değil (yok, başka günde ya da adresi doğrulanmamış): "
                            + unknown);
        }
        return c.routable().stream().filter(s -> wanted.contains(s.getId())).toList();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("karsilastirma JSON'a yazilamadi", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("karsilastirma JSON'u okunamadi", e);
        }
    }

    private static BigDecimal scale(double v, int digits) {
        return BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP);
    }
}
