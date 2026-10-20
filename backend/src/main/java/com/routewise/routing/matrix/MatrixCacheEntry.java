package com.routewise.routing.matrix;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One cell of a cached matrix. Schema owned by Liquibase. */
@Entity
@Table(name = "distance_matrix_cache")
public class MatrixCacheEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_set_hash", nullable = false, length = 64)
    private String nodeSetHash;

    @Column(name = "from_node_id", nullable = false)
    private Long fromNodeId;

    @Column(name = "to_node_id", nullable = false)
    private Long toNodeId;

    @Column(name = "distance_m", nullable = false)
    private Double distanceM;

    @Column(name = "duration_s", nullable = false)
    private Integer durationS;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Instant.now();

    protected MatrixCacheEntry() {}

    public MatrixCacheEntry(String nodeSetHash, Long fromNodeId, Long toNodeId,
                            Double distanceM, Integer durationS) {
        this.nodeSetHash = nodeSetHash;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.distanceM = distanceM;
        this.durationS = durationS;
    }

    public String getNodeSetHash() { return nodeSetHash; }
    public Long getFromNodeId() { return fromNodeId; }
    public Long getToNodeId() { return toNodeId; }
    public Double getDistanceM() { return distanceM; }
    public Integer getDurationS() { return durationS; }
}
