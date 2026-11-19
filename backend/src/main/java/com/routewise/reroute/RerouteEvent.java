package com.routewise.reroute;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
 * One reroute of a dispatched route. Schema owned by Liquibase ({@code 034-reroute}).
 *
 * <p>Measurement data like {@code optimization_runs}: written once, never updated.
 * {@code keptOrderCostTry} is the remaining tour in the old order with the removed
 * stop skipped — what the courier would have driven without the reroute.
 *
 * <p>{@link Changes} is what the courier's "route changed" screen (K5) lists;
 * {@code orderKept} rows (daily limit reached, {@code 035}) record a change that
 * did not re-sequence the route and do not count towards that limit.
 */
@Entity
@Table(name = "reroute_events")
public class RerouteEvent {

    /** Names match {@code ck_reroute_trigger}. */
    public enum Trigger { ERTELEME, ADRES_DEGISIKLIGI }

    /** Why a stop left the route; solver reasons ({@code EXCEEDS_WORKDAY}, …) pass through as they are. */
    public static final String RECIPIENT_POSTPONED = "ALICI_ERTELEDI";
    public static final String ADDRESS_REJECTED = "ADRES_REDDEDILDI";
    /** The company accepted a postpone suggestion (cost threshold). */
    public static final String COMPANY_POSTPONED = "FIRMA_ERTELEDI";

    /** A stop that left the route in this reroute. */
    public record Removed(Long stopId, Long shipmentId, String orderNumber, String reason) {}

    /** An open stop whose address was approved at a new road point. */
    public record Moved(Long stopId, Long shipmentId, String orderNumber) {}

    /** An open stop that changed place among the remaining ones; sequence numbers as the courier saw them. */
    public record Reordered(Long stopId, Long shipmentId, String orderNumber, int fromSeq, int toSeq) {}

    public record Changes(List<Removed> removed, List<Moved> moved, List<Reordered> reordered) {
        public Changes {
            removed = removed == null ? List.of() : List.copyOf(removed);
            moved = moved == null ? List.of() : List.copyOf(moved);
            reordered = reordered == null ? List.of() : List.copyOf(reordered);
        }
    }

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

    @Column(name = "order_kept", nullable = false)
    private boolean orderKept;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Changes changes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RerouteEvent() {}

    public RerouteEvent(Long routeId, Trigger trigger, Long shipmentId, Long startNodeId,
                        int openStopsBefore, int openStopsAfter, int autoPostponed,
                        BigDecimal keptOrderCostTry, BigDecimal newCostTry, Integer computationTimeMs,
                        boolean orderKept, Changes changes, Instant createdAt) {
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
        this.orderKept = orderKept;
        this.changes = changes;
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
    public boolean isOrderKept() { return orderKept; }
    public Changes getChanges() { return changes; }
    public Instant getCreatedAt() { return createdAt; }
}
