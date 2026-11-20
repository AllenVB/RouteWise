package com.routewise.comparison;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One algorithm's row in a level-2 comparison ({@code 038}). A scenario is a
 * comparison; the significance tests pair algorithms within it.
 */
@Entity
@Table(name = "tour_comparison_runs")
public class TourComparisonRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comparison_id", nullable = false)
    private Long comparisonId;

    @Column(nullable = false)
    private String algorithm;

    @Column(name = "stop_count", nullable = false)
    private int stopCount;

    @Column(nullable = false)
    private boolean feasible;

    private BigDecimal objective;

    @Column(name = "cost_try")
    private BigDecimal costTry;

    @Column(name = "distance_m")
    private BigDecimal distanceM;

    @Column(name = "duration_s")
    private BigDecimal durationS;

    @Column(name = "computation_time_ms", nullable = false)
    private BigDecimal computationTimeMs;

    @Column(name = "gap_pct")
    private BigDecimal gapPct;

    @Column(name = "reference_is_optimal", nullable = false)
    private boolean referenceIsOptimal;

    @Column(name = "random_seed")
    private Long randomSeed;

    @Column(name = "objective_min")
    private BigDecimal objectiveMin;

    @Column(name = "objective_max")
    private BigDecimal objectiveMax;

    private Long explored;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TourComparisonRun() {}

    @SuppressWarnings("java:S107")   // bir ölçüm satırı: alanların hepsi zorunlu bağlam
    public TourComparisonRun(Long comparisonId, String algorithm, int stopCount, boolean feasible,
                             BigDecimal objective, BigDecimal costTry, BigDecimal distanceM, BigDecimal durationS,
                             BigDecimal computationTimeMs, BigDecimal gapPct, boolean referenceIsOptimal,
                             Long randomSeed, BigDecimal objectiveMin, BigDecimal objectiveMax, Long explored,
                             Instant createdAt) {
        this.comparisonId = comparisonId;
        this.algorithm = algorithm;
        this.stopCount = stopCount;
        this.feasible = feasible;
        this.objective = objective;
        this.costTry = costTry;
        this.distanceM = distanceM;
        this.durationS = durationS;
        this.computationTimeMs = computationTimeMs;
        this.gapPct = gapPct;
        this.referenceIsOptimal = referenceIsOptimal;
        this.randomSeed = randomSeed;
        this.objectiveMin = objectiveMin;
        this.objectiveMax = objectiveMax;
        this.explored = explored;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getComparisonId() { return comparisonId; }
    public String getAlgorithm() { return algorithm; }
    public boolean isFeasible() { return feasible; }
    public BigDecimal getGapPct() { return gapPct; }
}
