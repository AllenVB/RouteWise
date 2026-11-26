package com.routewise.comparison;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One algorithm comparison (F8). Schema owned by Liquibase ({@code 037}).
 *
 * <p>{@code params} and {@code result} are kept as JSON text: the two levels
 * carry different shapes and the screen redraws a past comparison exactly as
 * it was computed. Written once, never updated — measurement data.
 */
@Entity
@Table(name = "comparisons")
public class Comparison {

    /** Names match {@code ck_comparison_level}. */
    public enum Level { PATH, TOUR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Level level;

    @Column(name = "plan_id")
    private Long planId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String params;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String result;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "experiment_id")
    private Long experimentId;

    @Column(name = "scenario_seed")
    private Long scenarioSeed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Comparison() {}

    public Comparison(Level level, Long planId, String params, String result, Long createdBy, Instant createdAt) {
        this.level = level;
        this.planId = planId;
        this.params = params;
        this.result = result;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** A synthetic scenario of an experiment: no plan, reproducible from its seed. */
    static Comparison scenario(Long experimentId, long seed, String params, String result, Long createdBy,
                               Instant createdAt) {
        Comparison c = new Comparison(Level.TOUR, null, params, result, createdBy, createdAt);
        c.experimentId = experimentId;
        c.scenarioSeed = seed;
        return c;
    }

    public Long getId() { return id; }
    public Level getLevel() { return level; }
    public Long getPlanId() { return planId; }
    public String getParams() { return params; }
    public String getResult() { return result; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getExperimentId() { return experimentId; }
    public Long getScenarioSeed() { return scenarioSeed; }
}
