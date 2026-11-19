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
 * One algorithm's row in a level-1 comparison — what the analysis and the
 * significance tests read ({@code 006}, extended in {@code 037}).
 */
@Entity
@Table(name = "path_comparison_runs")
public class PathComparisonRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comparison_id")
    private Long comparisonId;

    @Column(nullable = false)
    private String algorithm;

    @Column(name = "from_node_id", nullable = false)
    private Long fromNodeId;

    @Column(name = "to_node_id", nullable = false)
    private Long toNodeId;

    @Column(nullable = false)
    private String weight;

    @Column(nullable = false)
    private boolean found;

    @Column(name = "path_length_m")
    private BigDecimal pathLengthM;

    @Column(name = "path_duration_s")
    private BigDecimal pathDurationS;

    @Column(name = "edge_count")
    private Integer edgeCount;

    @Column(name = "computation_time_ms")
    private BigDecimal computationTimeMs;

    @Column(name = "nodes_visited")
    private Integer nodesVisited;

    @Column(name = "deviation_pct")
    private BigDecimal deviationPct;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PathComparisonRun() {}

    public PathComparisonRun(Long comparisonId, String algorithm, Long fromNodeId, Long toNodeId, String weight,
                             boolean found, BigDecimal pathLengthM, BigDecimal pathDurationS, Integer edgeCount,
                             BigDecimal computationTimeMs, Integer nodesVisited, BigDecimal deviationPct,
                             Instant createdAt) {
        this.comparisonId = comparisonId;
        this.algorithm = algorithm;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.weight = weight;
        this.found = found;
        this.pathLengthM = pathLengthM;
        this.pathDurationS = pathDurationS;
        this.edgeCount = edgeCount;
        this.computationTimeMs = computationTimeMs;
        this.nodesVisited = nodesVisited;
        this.deviationPct = deviationPct;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getComparisonId() { return comparisonId; }
    public String getAlgorithm() { return algorithm; }
    public boolean isFound() { return found; }
    public BigDecimal getDeviationPct() { return deviationPct; }
    public Integer getNodesVisited() { return nodesVisited; }
}
