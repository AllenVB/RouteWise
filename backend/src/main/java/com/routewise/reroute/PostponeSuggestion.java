package com.routewise.reroute;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * "This address move makes today's round too expensive; postpone it?" — raised by a
 * reroute, decided by the company. Schema owned by Liquibase ({@code 036}).
 */
@Entity
@Table(name = "postpone_suggestions")
public class PostponeSuggestion {

    /** Names match {@code ck_suggestion_status}. */
    public enum Status {
        ACIK,
        /** the company postponed the shipment to the next day */
        ERTELENDI,
        /** the company kept it on today's round */
        KALDI,
        /** a later reroute found the stop cheap enough, or the courier closed it first */
        GECERSIZ
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "stop_id", nullable = false)
    private Long stopId;

    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    @Column(name = "reroute_event_id")
    private Long rerouteEventId;

    @Column(name = "cost_without_try", nullable = false)
    private BigDecimal costWithoutTry;

    @Column(name = "cost_with_try", nullable = false)
    private BigDecimal costWithTry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    protected PostponeSuggestion() {}

    public PostponeSuggestion(Long routeId, Long stopId, Long shipmentId, Long rerouteEventId,
                              BigDecimal costWithoutTry, BigDecimal costWithTry, Instant createdAt) {
        this.routeId = routeId;
        this.stopId = stopId;
        this.shipmentId = shipmentId;
        this.rerouteEventId = rerouteEventId;
        this.costWithoutTry = costWithoutTry;
        this.costWithTry = costWithTry;
        this.status = Status.ACIK;
        this.createdAt = createdAt;
    }

    /** A later reroute measured the same stop again: the newest numbers stand. */
    void remeasure(Long rerouteEventId, BigDecimal costWithoutTry, BigDecimal costWithTry, Instant at) {
        this.rerouteEventId = rerouteEventId;
        this.costWithoutTry = costWithoutTry;
        this.costWithTry = costWithTry;
        this.createdAt = at;
    }

    void decide(Status outcome, Long userId, Instant at) {
        this.status = outcome;
        this.decidedBy = userId;
        this.decidedAt = at;
    }

    public boolean isOpen() {
        return status == Status.ACIK;
    }

    public BigDecimal extraCostTry() {
        return costWithTry.subtract(costWithoutTry);
    }

    /** Extra cost over the round without the stop; null when that round costs nothing. */
    public BigDecimal extraRatio() {
        if (costWithoutTry.signum() == 0) {
            return null;
        }
        return extraCostTry().divide(costWithoutTry, 4, RoundingMode.HALF_UP);
    }

    public Long getId() { return id; }
    public Long getRouteId() { return routeId; }
    public Long getStopId() { return stopId; }
    public Long getShipmentId() { return shipmentId; }
    public Long getRerouteEventId() { return rerouteEventId; }
    public BigDecimal getCostWithoutTry() { return costWithoutTry; }
    public BigDecimal getCostWithTry() { return costWithTry; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Long getDecidedBy() { return decidedBy; }
}
