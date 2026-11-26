package com.routewise.optimization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlannedRouteRepository extends JpaRepository<PlannedRoute, Long> {

    List<PlannedRoute> findByRunIdOrderByIdAsc(Long runId);

    /**
     * The courier's route on a day: on the applied run of a dispatched plan.
     * Draft runs are never a courier's route — only what was dispatched is.
     */
    @Query("""
            SELECT r FROM PlannedRoute r, OptimizationRun run, DeliveryPlan p
            WHERE r.runId = run.id AND run.planId = p.id
              AND run.applied = true
              AND p.status <> com.routewise.plan.PlanStatus.TASLAK
              AND r.courierId = :courierId AND p.planDate = :date
            """)
    Optional<PlannedRoute> findDispatchedFor(@Param("courierId") Long courierId, @Param("date") LocalDate date);

    /** Row lock while closing stops: two taps on "delivered" must not both start the route. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PlannedRoute r WHERE r.id = :id")
    Optional<PlannedRoute> lockById(@Param("id") Long id);
}
