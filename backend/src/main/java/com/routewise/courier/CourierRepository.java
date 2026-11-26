package com.routewise.courier;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CourierRepository extends JpaRepository<Courier, Long> {

    List<Courier> findByStatusOrderByIdAsc(String status);

    Optional<Courier> findByUserId(Long userId);
}
