package com.routewise.optimization;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One algorithm run over a plan — the measurement backbone. Savings percentages
 * and every later comparison are derived from these rows, so a run is never
 * edited after it is written; only {@code applied} moves between runs.
 */
@Entity
@Table(name = "optimization_runs")
public class OptimizationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Algorithm algorithm;

    @Column(name = "is_applied", nullable = false)
    private boolean applied;

    @Column(name = "is_baseline", nullable = false)
    private boolean baseline;

    @Column(name = "total_distance_m")
    private BigDecimal totalDistanceM;

    @Column(name = "total_duration_s")
    private Integer totalDurationS;

    @Column(name = "total_fuel_l")
    private BigDecimal totalFuelL;

    @Column(name = "total_cost_try")
    private BigDecimal totalCostTry;

    @Column(name = "vehicle_count")
    private Short vehicleCount;

    @Column(name = "computation_time_ms")
    private Integer computationTimeMs;

    @Column(name = "random_seed")
    private Long randomSeed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OptimizationRun() {}

    public OptimizationRun(Long planId, Algorithm algorithm, boolean applied, boolean baseline,
                           BigDecimal totalDistanceM, Integer totalDurationS,
                           Short vehicleCount, Integer computationTimeMs) {
        this(planId, algorithm, applied, baseline, totalDistanceM, totalDurationS, vehicleCount,
                computationTimeMs, null, null, null);
    }

    /**
     * @param totalCostTry real money only (fuel + driver + fixed); the priority
     *                     penalty is an objective term and is not stored as cost
     * @param randomSeed   null for algorithms that use no randomness
     */
    public OptimizationRun(Long planId, Algorithm algorithm, boolean applied, boolean baseline,
                           BigDecimal totalDistanceM, Integer totalDurationS,
                           Short vehicleCount, Integer computationTimeMs,
                           BigDecimal totalFuelL, BigDecimal totalCostTry, Long randomSeed) {
        this.planId = planId;
        this.algorithm = algorithm;
        this.applied = applied;
        this.baseline = baseline;
        this.totalDistanceM = totalDistanceM;
        this.totalDurationS = totalDurationS;
        this.vehicleCount = vehicleCount;
        this.computationTimeMs = computationTimeMs;
        this.totalFuelL = totalFuelL;
        this.totalCostTry = totalCostTry;
        this.randomSeed = randomSeed;
    }

    public Long getId() { return id; }
    public Long getPlanId() { return planId; }
    public Algorithm getAlgorithm() { return algorithm; }
    public boolean isApplied() { return applied; }
    public boolean isBaseline() { return baseline; }
    public BigDecimal getTotalDistanceM() { return totalDistanceM; }
    public Integer getTotalDurationS() { return totalDurationS; }
    public BigDecimal getTotalFuelL() { return totalFuelL; }
    public BigDecimal getTotalCostTry() { return totalCostTry; }
    public Short getVehicleCount() { return vehicleCount; }
    public Integer getComputationTimeMs() { return computationTimeMs; }
    public Long getRandomSeed() { return randomSeed; }
    public Instant getCreatedAt() { return createdAt; }
}
