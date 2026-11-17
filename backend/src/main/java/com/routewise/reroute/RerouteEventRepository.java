package com.routewise.reroute;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RerouteEventRepository extends JpaRepository<RerouteEvent, Long> {

    List<RerouteEvent> findByRouteIdOrderByIdAsc(Long routeId);
}
