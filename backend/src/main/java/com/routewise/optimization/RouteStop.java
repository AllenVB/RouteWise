package com.routewise.optimization;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A stop in visiting order; sequence starts at 1, the depot is not a row. */
@Entity
@Table(name = "route_stops")
public class RouteStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    /** Graph node at run time — the run stays reproducible if the address moves later. */
    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "distance_from_prev_m")
    private BigDecimal distanceFromPrevM;

    @Column(name = "duration_from_prev_s")
    private Integer durationFromPrevS;

    @Column
    private Instant eta;

    @Column(nullable = false)
    private String status = "BEKLEMEDE";

    protected RouteStop() {}

    public RouteStop(Long routeId, Long shipmentId, Integer sequenceNo, Long nodeId,
                     BigDecimal distanceFromPrevM, Integer durationFromPrevS) {
        this(routeId, shipmentId, sequenceNo, nodeId, distanceFromPrevM, durationFromPrevS, null);
    }

    /** @param eta planned arrival: plan day + depot opening time + travel and earlier service */
    public RouteStop(Long routeId, Long shipmentId, Integer sequenceNo, Long nodeId,
                     BigDecimal distanceFromPrevM, Integer durationFromPrevS, Instant eta) {
        this.routeId = routeId;
        this.shipmentId = shipmentId;
        this.sequenceNo = sequenceNo;
        this.nodeId = nodeId;
        this.distanceFromPrevM = distanceFromPrevM;
        this.durationFromPrevS = durationFromPrevS;
        this.eta = eta;
    }

    public Long getId() { return id; }
    public Long getRouteId() { return routeId; }
    public Long getShipmentId() { return shipmentId; }
    public Integer getSequenceNo() { return sequenceNo; }
    public Long getNodeId() { return nodeId; }
    public BigDecimal getDistanceFromPrevM() { return distanceFromPrevM; }
    public Integer getDurationFromPrevS() { return durationFromPrevS; }
    public Instant getEta() { return eta; }
    public String getStatus() { return status; }
}
