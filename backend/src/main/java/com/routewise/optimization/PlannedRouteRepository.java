package com.routewise.optimization;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlannedRouteRepository extends JpaRepository<PlannedRoute, Long> {

    List<PlannedRoute> findByRunIdOrderByIdAsc(Long runId);
}
