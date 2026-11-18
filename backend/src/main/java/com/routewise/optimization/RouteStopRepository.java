package com.routewise.optimization;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouteStopRepository extends JpaRepository<RouteStop, Long> {

    List<RouteStop> findByRouteIdInOrderByRouteIdAscSequenceNoAsc(Collection<Long> routeIds);

    List<RouteStop> findByRouteIdOrderBySequenceNoAsc(Long routeId);

    /**
     * Stops of the given shipments on dispatched plans, newest first — what the
     * recipient is told. Draft runs are excluded: an ETA from a plan that never
     * left the depot would be a promise nobody made.
     */
    @Query("""
            SELECT s FROM RouteStop s, PlannedRoute r, OptimizationRun run, DeliveryPlan p
            WHERE s.routeId = r.id AND r.runId = run.id AND run.planId = p.id
              AND run.applied = true
              AND p.status <> com.routewise.plan.PlanStatus.TASLAK
              AND s.shipmentId IN :ids
            ORDER BY s.id DESC
            """)
    List<RouteStop> findDispatchedForShipments(@Param("ids") Collection<Long> shipmentIds);

    /**
     * The route of a stop, as a scalar. Callers lock the route first and load
     * the stop afterwards: a stop entity read before the lock stays cached in the
     * persistence context and would hide a concurrent close.
     */
    @Query("SELECT s.routeId FROM RouteStop s WHERE s.id = :id")
    Optional<Long> findRouteIdOf(@Param("id") Long stopId);

    /** Routes on which the shipment still has an open stop, on a dispatched plan. */
    @Query("""
            SELECT s.routeId FROM RouteStop s, PlannedRoute r, OptimizationRun run, DeliveryPlan p
            WHERE s.routeId = r.id AND r.runId = run.id AND run.planId = p.id
              AND run.applied = true
              AND p.status = com.routewise.plan.PlanStatus.SEVK_EDILDI
              AND s.shipmentId = :shipmentId
              AND s.status = 'BEKLEMEDE'
            """)
    List<Long> findOpenDispatchedRouteIds(@Param("shipmentId") Long shipmentId);

    /**
     * {@code [routeId, shipmentId]} of open stops on dispatched plans whose
     * shipment goes to this address. Scalars, for the same reason as
     * {@link #findRouteIdOf}: the caller locks each route before loading stops.
     */
    @Query("""
            SELECT s.routeId, s.shipmentId FROM RouteStop s, Shipment sh, PlannedRoute r,
                   OptimizationRun run, DeliveryPlan p
            WHERE s.shipmentId = sh.id AND s.routeId = r.id AND r.runId = run.id AND run.planId = p.id
              AND run.applied = true
              AND p.status = com.routewise.plan.PlanStatus.SEVK_EDILDI
              AND sh.addressId = :addressId
              AND s.status = 'BEKLEMEDE'
            ORDER BY s.routeId, s.shipmentId
            """)
    List<Object[]> findOpenDispatchedByAddress(@Param("addressId") Long addressId);
}
