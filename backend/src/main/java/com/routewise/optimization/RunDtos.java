package com.routewise.optimization;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class RunDtos {

    private RunDtos() {}

    /**
     * Every field optional.
     *
     * @param algorithm          default ROUTEWISE
     * @param vehicleId          plan with this one vehicle only (single-vehicle scenario);
     *                           default: the whole active fleet
     * @param randomSeed         ROUTEWISE only; drawn by the optimizer when absent and
     *                           returned either way
     * @param warmStartFromRunId ROUTEWISE only; continue from that run of the same plan
     *                           so only routes that receive new stops change
     */
    public record OptimizeRequest(Algorithm algorithm, Long vehicleId, Long randomSeed,
                                  Long warmStartFromRunId) {}

    /**
     * Result of {@code POST /plans/{id}/optimize}.
     *
     * <p>{@code savingsPct} is on real money ({@code totalCostTry}: fuel + driver +
     * vehicle fixed cost) against the naive baseline; {@code distanceSavingsPct}
     * is kept because distance is what the map shows. {@code priorityPenalty} is
     * the objective's lateness term — it steers the algorithm but is not money
     * and is never part of the cost.
     */
    public record OptimizeResponse(
            Long runId,
            Algorithm algorithm,
            boolean isApplied,
            BigDecimal totalDistanceM,
            Integer totalDurationS,
            BigDecimal totalFuelL,
            BigDecimal totalCostTry,
            Short vehicleCount,
            Integer computationTimeMs,
            Long randomSeed,
            BigDecimal priorityPenalty,
            BaselineSummary baseline,
            String baselineError,
            BigDecimal savingsPct,
            BigDecimal distanceSavingsPct,
            List<RouteSummary> routes,
            List<ExcludedShipment> excluded) {}

    public record BaselineSummary(
            Long runId,
            Algorithm algorithm,
            BigDecimal totalDistanceM,
            Integer totalDurationS,
            BigDecimal totalCostTry) {}

    public record RouteSummary(Long id, Long vehicleId, Long courierId, int stopCount) {}

    /**
     * A shipment the run left out, and why.
     *
     * @param reason {@code ADDRESS_NOT_VERIFIED} — address not approved (or moved
     *               since); {@code NO_ROAD_NODE} — approved but never snapped to the
     *               road graph (a record written outside the verification flow);
     *               {@code UNREACHABLE} — no road from the depot and back
     */
    public record ExcludedShipment(Long shipmentId, String orderNumber, String reason) {}

    public record RunSummary(
            Long runId,
            Long planId,
            Algorithm algorithm,
            boolean isApplied,
            boolean isBaseline,
            BigDecimal totalDistanceM,
            Integer totalDurationS,
            BigDecimal totalFuelL,
            BigDecimal totalCostTry,
            Short vehicleCount,
            Integer computationTimeMs,
            Long randomSeed,
            Instant createdAt) {}

    /**
     * {@code geometryAvailable=false} means the optimizer could not be reached for
     * drawing; the stops and metrics are still the stored truth.
     */
    public record RunDetail(
            RunSummary run,
            DepotPoint depot,
            List<RouteDetail> routes,
            boolean geometryAvailable) {}

    public record DepotPoint(String name, Double lat, Double lon) {}

    public record RouteDetail(
            Long id,
            Long vehicleId,
            String plate,
            BigDecimal totalDistanceM,
            Integer totalDurationS,
            BigDecimal totalLoadKg,
            BigDecimal totalFuelL,
            BigDecimal totalCostTry,
            List<StopDetail> stops,
            LineString geometry) {}

    public record StopDetail(
            Long id,
            Integer sequenceNo,
            Long shipmentId,
            String orderNumber,
            String addressText,
            Double lat,
            Double lon,
            BigDecimal distanceFromPrevM,
            Integer durationFromPrevS,
            Instant eta,
            String status) {}

    /** GeoJSON geometry — MapLibre draws it as is. Coordinates are {@code [lon, lat]}. */
    public record LineString(String type, List<double[]> coordinates) {
        public static LineString of(List<double[]> coordinates) {
            return new LineString("LineString", coordinates);
        }
    }
}
