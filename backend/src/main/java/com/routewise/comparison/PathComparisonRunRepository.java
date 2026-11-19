package com.routewise.comparison;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PathComparisonRunRepository extends JpaRepository<PathComparisonRun, Long> {

    List<PathComparisonRun> findByComparisonIdOrderByIdAsc(Long comparisonId);
}
