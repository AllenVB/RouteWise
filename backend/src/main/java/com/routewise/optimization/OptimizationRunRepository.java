package com.routewise.optimization;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OptimizationRunRepository extends JpaRepository<OptimizationRun, Long> {

    List<OptimizationRun> findByPlanIdOrderByIdDesc(Long planId);

    Optional<OptimizationRun> findByPlanIdAndAppliedTrue(Long planId);

    /**
     * Withdraws the plan's applied run before a new one is written.
     *
     * <p>Must run and flush first: {@code uq_runs_one_applied} is checked per
     * statement, so inserting the new applied run before this update fails.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OptimizationRun r SET r.applied = false WHERE r.planId = :planId AND r.applied = true")
    int withdrawApplied(@Param("planId") Long planId);
}
