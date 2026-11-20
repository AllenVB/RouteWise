package com.routewise.comparison;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TourComparisonRunRepository extends JpaRepository<TourComparisonRun, Long> {

    List<TourComparisonRun> findByComparisonIdOrderByIdAsc(Long comparisonId);
}
