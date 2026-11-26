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
 * N synthetic level-2 scenarios and their statistics. Schema owned by Liquibase ({@code 039}).
 * Same {@code baseSeed} and parameters reproduce the same experiment.
 */
@Entity
@Table(name = "experiments")
public class Experiment {

    /** Names match {@code ck_experiment_status}. */
    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String kind = "TOUR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "scenario_count", nullable = false)
    private int scenarioCount;

    @Column(name = "stop_count", nullable = false)
    private int stopCount;

    @Column(name = "radius_m", nullable = false)
    private int radiusM;

    @Column(nullable = false)
    private int repeats;

    @Column(name = "base_seed", nullable = false)
    private long baseSeed;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(nullable = false)
    private int completed;

    @Column(nullable = false)
    private int failed;

    @Column(name = "last_error")
    private String lastError;

    @JdbcTypeCode(SqlTypes.JSON)
    private String summary;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Experiment() {}

    public Experiment(int scenarioCount, int stopCount, int radiusM, int repeats, long baseSeed, Long vehicleId,
                      Long createdBy, Instant createdAt) {
        this.status = Status.QUEUED;
        this.scenarioCount = scenarioCount;
        this.stopCount = stopCount;
        this.radiusM = radiusM;
        this.repeats = repeats;
        this.baseSeed = baseSeed;
        this.vehicleId = vehicleId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    void start(Instant at) {
        this.status = Status.RUNNING;
        this.startedAt = at;
    }

    void scenarioDone() {
        this.completed++;
    }

    /** A scenario that could not be built or solved (e.g. no feasible tour): skipped, counted. */
    void scenarioFailed(String why) {
        this.failed++;
        this.lastError = truncate(why);
    }

    void finish(String summaryJson, Instant at) {
        this.status = Status.DONE;
        this.summary = summaryJson;
        this.finishedAt = at;
    }

    void abort(String why, Instant at) {
        this.status = Status.FAILED;
        this.lastError = truncate(why);
        this.finishedAt = at;
    }

    public boolean isActive() {
        return status == Status.QUEUED || status == Status.RUNNING;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    public Long getId() { return id; }
    public Status getStatus() { return status; }
    public int getScenarioCount() { return scenarioCount; }
    public int getStopCount() { return stopCount; }
    public int getRadiusM() { return radiusM; }
    public int getRepeats() { return repeats; }
    public long getBaseSeed() { return baseSeed; }
    public Long getVehicleId() { return vehicleId; }
    public int getCompleted() { return completed; }
    public int getFailed() { return failed; }
    public String getLastError() { return lastError; }
    public String getSummary() { return summary; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
