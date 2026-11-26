package com.routewise.comparison;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ComparisonRepository extends JpaRepository<Comparison, Long> {

    List<Comparison> findByExperimentIdOrderByScenarioSeedAsc(Long experimentId);
}
