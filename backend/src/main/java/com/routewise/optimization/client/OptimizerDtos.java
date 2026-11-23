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
     * @param startNodeId rerouting on the road: vehicles leave from here (the
     *                   courier's last closed stop) and still return to the depot;
     *                   null = the depot. arrivalS and maxRouteDurationS count from it
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
            Double maxRouteDurationS,
            Long startNodeId) {}

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

    /** Level 2: one vehicle, every tour algorithm on the same stops. */
    public record TourCompareRequest(long depotNodeId, List<StopIn> stops, List<VehicleIn> vehicles,
                                     MatrixIn matrix, CostParamsIn costParams, Long randomSeed,
                                     Double maxRouteDurationS, int repeats) {}

    /**
     * @param referenceIsOptimal true: gaps are over the proven optimum (DFS_EXHAUSTIVE);
     *                           false: more than 10 stops, gaps are over the best found
     */
    public record TourCompareResult(int repeats, List<Long> seeds, String referenceAlgorithm,
                                    boolean referenceIsOptimal, String exhaustiveSkipped,
                                    List<TourAlgorithmOut> results, List<UnassignedOut> unassigned) {}

    /**
     * @param solution          null when this algorithm could not build a feasible tour ({@code error})
     * @param computationTimeMs median of the repeats
     * @param randomSeed        ROUTEWISE only: seed of the reported (median) run
     * @param explored          DFS_EXHAUSTIVE only: search nodes opened
     */
    public record TourAlgorithmOut(String algorithm, boolean feasible, String error, SolutionOut solution,
                                   double computationTimeMs, Double gapPct, Long randomSeed,
                                   Double objectiveMin, Double objectiveMax, Long explored) {}

    // --- /internal/experiments, /internal/analysis ---

    /** One synthetic scenario from {@code seed}: stops drawn around the depot on the real road graph. */
    public record TourScenarioRequest(long depotNodeId, long seed, int stopCount, double radiusM, VehicleIn vehicle,
                                      CostParamsIn costParams, Double maxRouteDurationS, int repeats) {}

    public record TourScenarioResult(long seed, List<StopIn> stops, TourCompareResult comparison) {}

    public record PairIn(String candidate, String reference, List<Double> candidateValues,
                         List<Double> referenceValues) {}

    public record PairedRequest(List<PairIn> pairs, double alpha) {}

    /**
     * One-sided (H1: candidate cheaper than reference), paired over scenarios.
     *
     * @param meanDiff candidate minus reference; negative means the candidate is cheaper
     */
    public record PairedOut(String candidate, String reference, int n, int candidateBetter, int ties,
                            int candidateWorse, double meanDiff, double medianDiff, double meanDiffPct,
                            Double wilcoxonStatistic, double wilcoxonP, Double tStatistic, double tP,
                            boolean significant, String note) {}

    public record PairedResult(double alpha, List<PairedOut> results) {}

    // --- /internal/analysis/savings, /internal/reports/export ---

    /** One dispatched plan: the applied run against the naive baseline of the same shipments. */
    public record PlanRowIn(java.time.LocalDate planDate, double optimizedCostTry, double baselineCostTry,
                            double optimizedDistanceM, double baselineDistanceM, int vehicleCount,
                            int shipmentCount) {}

    /** @param period {@code WEEK}, {@code MONTH} or {@code YEAR} */
    public record SavingsRequest(String period, List<PlanRowIn> rows) {}

    /** @param savingsPct weighted: (Σ baseline − Σ optimized) / Σ baseline; null when the baseline costs nothing */
    public record BucketOut(java.time.LocalDate start, java.time.LocalDate end, int plans, int shipments,
                            double optimizedCostTry, double baselineCostTry, double savingsTry, Double savingsPct,
                            double optimizedDistanceM, double baselineDistanceM, Double distanceSavingsPct) {}

    public record SavingsResult(String period, List<BucketOut> buckets, BucketOut total) {}

    public record PathStatIn(String algorithm, int runs, double medianDeviationPct, double maxDeviationPct,
                             double medianNodesVisited, double medianTimeMs) {}

    public record ExperimentAlgorithmIn(String algorithm, int scenarios, double medianGapPct, double meanGapPct,
                                        double maxGapPct, int optimalCount, double medianTimeMs) {}

    public record ExperimentTestIn(String candidate, String reference, int n, int candidateBetter, int ties,
                                   double meanDiffPct, double wilcoxonP, double tP, boolean significant) {}

    /** @param gaps algorithm -> gap over the optimum (%) per scenario, for the distribution figure */
    public record ExperimentIn(long id, long baseSeed, int scenarios, int stopCount,
                               List<ExperimentAlgorithmIn> algorithms, List<ExperimentTestIn> tests, String claim,
                               java.util.Map<String, List<Double>> gaps) {}

    /** @param format {@code XLSX} or {@code PDF}; the response body is the file */
    public record ExportRequest(String format, String period, java.time.LocalDate dateFrom,
                                java.time.LocalDate dateTo, java.time.LocalDateTime generatedAt,
                                List<PlanRowIn> rows, List<PathStatIn> pathStats, ExperimentIn experiment) {}

    /** @param weight {@code distance} or {@code duration} */
    public record PathCompareRequest(long fromNodeId, long toNodeId, String weight, int repeats) {}

    public record PathCompareResult(long fromNodeId, long toNodeId, String weight, int repeats,
                                    List<PathAlgorithmOut> results) {}

    /**
     * @param computationTimeMs median of {@code repeats} runs
     * @param deviationPct      over Dijkstra in the chosen weight; null when no path exists
     * @param coordinates       {@code [lon, lat]} pairs along the path
     */
    public record PathAlgorithmOut(String algorithm, boolean found, double lengthM, double durationS,
                                   int edgeCount, int nodesVisited, double computationTimeMs,
                                   Double deviationPct, boolean samePathAsReference, List<Long> path,
                                   List<double[]> coordinates) {}

    public record GeometryResult(List<LegGeometry> legs, double totalLengthM) {}

    /** Coordinates are {@code [lon, lat]} pairs — GeoJSON order. */
    public record LegGeometry(long fromNodeId, long toNodeId, double lengthM, List<double[]> coordinates) {}
}
