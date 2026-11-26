package com.routewise.plan;

import java.time.LocalDate;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryPlanRepository extends JpaRepository<DeliveryPlan, Long> {

    Optional<DeliveryPlan> findByDepotIdAndPlanDate(Long depotId, LocalDate planDate);

    /**
     * Row lock for persisting a run: two concurrent optimizations of the same
     * plan would otherwise both try to become the applied run and the second
     * would fail on {@code uq_runs_one_applied} with an opaque 500.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM DeliveryPlan p WHERE p.id = :id")
    Optional<DeliveryPlan> lockById(@Param("id") Long id);
}
