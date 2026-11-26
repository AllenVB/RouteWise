package com.routewise.comparison;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentRepository extends JpaRepository<Experiment, Long> {

    List<Experiment> findTop20ByOrderByIdDesc();

    boolean existsByStatusIn(Collection<Experiment.Status> statuses);

    List<Experiment> findByStatusIn(Collection<Experiment.Status> statuses);

    java.util.Optional<Experiment> findFirstByStatusOrderByFinishedAtDescIdDesc(Experiment.Status status);
}
