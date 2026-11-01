package com.routewise.optimization.client;

import java.util.List;

/**
 * Wire format of the optimizer's {@code /internal} endpoints
 * ({@code python/app/schemas/}). Field names are the JSON names; the Python side
 * owns the contract and documents it in {@code python/README.md}.
 */
public final class OptimizerDtos {

    private OptimizerDtos() {}

    // --- /internal/matrix ---

    public record MatrixRequest(List<Long> nodeIds) {}

    /** {@code null} cell = unreachable. */
    public record MatrixResult(
            String nodeSetHash,
            List<Long> nodeIds,
            Double[][] distanceM,
            Double[][] durationS,
            int unreachablePairs) {}

    // --- /internal/optimize ---

    /**
     * @param costParams null keeps the phase 2 objective (distance)
     * @param randomSeed null lets the optimizer draw one; it is returned either way
     * @param warmStart  previous applied run; only the routes that receive new
     *                   stops change, so a courier's route is not reshuffled
     */
    public record OptimizeRequest(
            String algorithm,
            long depotNodeId,
            List<StopIn> stops,
            List<VehicleIn> vehicles,
            MatrixIn matrix,
            CostParamsIn costParams,
            Long randomSeed,
            List<WarmRouteIn> warmStart,
            Double maxRouteDurationS) {}

    /** {@code id} is the shipment id; it comes back unchanged as {@code stopId}. */
    public record StopIn(long id, long nodeId, double demandKg, double serviceTimeS, int priority) {}

    public record VehicleIn(long id, double capacityKg, double fuelLPer100km, double fixedCostPerDay) {}

    public record CostParamsIn(double fuelPricePerL, double driverCostPerHour, double priorityPenalty) {}

    public record WarmRouteIn(long vehicleId, List<Long> stopIds) {}

    public record MatrixIn(List<Long> nodeIds, Double[][] distanceM, Double[][] durationS) {}

    /**
     * @param baseline      null when the naive baseline cannot be built feasibly
     *                      (e.g. a tight workday); the optimized result still stands
     * @param baselineError why the baseline is missing
     */
    public record OptimizeResult(
            Long randomSeed,
            SolutionOut result,
            SolutionOut baseline,
            String baselineError,
            List<UnassignedOut> unassigned) {}

    /**
     * @param totalCostTry    real money: fuel + driver + vehicle fixed cost
     * @param priorityPenalty objective term, not money
     * @param objective       what the algorithm minimised: cost + penalty
     */
    public record SolutionOut(
            String algorithm,
            List<RouteOut> routes,
            double totalDistanceM,
            double totalDurationS,
            int vehicleCount,
            double computationTimeMs,
            double totalFuelL,
            Double totalCostTry,
            Double priorityPenalty,
            double objective) {}

    public record RouteOut(
            long vehicleId,
            List<StopOut> stops,
            double returnDistanceM,
            double returnDurationS,
            double totalDistanceM,
            double travelTimeS,
            double serviceTimeS,
            double totalDurationS,
            double totalLoadKg,
            double fuelL,
            Double costTry) {}

    public record StopOut(
            long stopId,
            long nodeId,
            int sequenceNo,
            double arrivalS,
            double distanceFromPrevM,
            double durationFromPrevS) {}

    public record UnassignedOut(long stopId, String reason) {}

    // --- /internal/route-geometry ---

    public record GeometryRequest(List<Long> nodeIds) {}

    public record GeometryResult(List<LegGeometry> legs, double totalLengthM) {}

    /** Coordinates are {@code [lon, lat]} pairs — GeoJSON order. */
    public record LegGeometry(long fromNodeId, long toNodeId, double lengthM, List<double[]> coordinates) {}
}
