package com.routewise.reroute;

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
 * One reroute of a dispatched route. Schema owned by Liquibase ({@code 034-reroute}).
 *
 * <p>Measurement data like {@code optimization_runs}: written once, never updated.
 * {@code keptOrderCostTry} is the remaining tour in the old order with the removed
 * stop skipped — what the courier would have driven without the reroute.
 */
@Entity
@Table(name = "reroute_events")
public class RerouteEvent {

    /** Names match {@code ck_reroute_trigger}. */
    public enum Trigger { ERTELEME, ADRES_DEGISIKLIGI }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private Trigger trigger;

    @Column(name = "shipment_id")
    private Long shipmentId;

    @Column(name = "start_node_id", nullable = false)
    private Long startNodeId;

    @Column(name = "open_stops_before", nullable = false)
    private int openStopsBefore;

    @Column(name = "open_stops_after", nullable = false)
    private int openStopsAfter;

    @Column(name = "auto_postponed", nullable = false)
    private int autoPostponed;

    @Column(name = "kept_order_cost_try")
    private BigDecimal keptOrderCostTry;

    @Column(name = "new_cost_try")
    private BigDecimal newCostTry;

    @Column(name = "computation_time_ms")
    private Integer computationTimeMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RerouteEvent() {}

    public RerouteEvent(Long routeId, Trigger trigger, Long shipmentId, Long startNodeId,
                        int openStopsBefore, int openStopsAfter, int autoPostponed,
                        BigDecimal keptOrderCostTry, BigDecimal newCostTry, Integer computationTimeMs,
                        Instant createdAt) {
        this.routeId = routeId;
        this.trigger = trigger;
        this.shipmentId = shipmentId;
        this.startNodeId = startNodeId;
        this.openStopsBefore = openStopsBefore;
        this.openStopsAfter = openStopsAfter;
        this.autoPostponed = autoPostponed;
        this.keptOrderCostTry = keptOrderCostTry;
        this.newCostTry = newCostTry;
        this.computationTimeMs = computationTimeMs;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getRouteId() { return routeId; }
    public Trigger getTrigger() { return trigger; }
    public Long getShipmentId() { return shipmentId; }
    public Long getStartNodeId() { return startNodeId; }
    public int getOpenStopsBefore() { return openStopsBefore; }
    public int getOpenStopsAfter() { return openStopsAfter; }
    public int getAutoPostponed() { return autoPostponed; }
    public BigDecimal getKeptOrderCostTry() { return keptOrderCostTry; }
    public BigDecimal getNewCostTry() { return newCostTry; }
    public Integer getComputationTimeMs() { return computationTimeMs; }
    public Instant getCreatedAt() { return createdAt; }
}
