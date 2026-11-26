package com.routewise.depot;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepotRepository extends JpaRepository<Depot, Long> {

    Optional<Depot> findFirstByOrderByIdAsc();
}
