package com.routewise.cost;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CostParametersRepository extends JpaRepository<CostParameters, Long> {

    /** The row in force on {@code date}: the latest one that started on or before it. */
    Optional<CostParameters> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(LocalDate date);
}
