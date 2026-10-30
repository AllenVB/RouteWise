package com.routewise.optimization;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteStopRepository extends JpaRepository<RouteStop, Long> {

    List<RouteStop> findByRouteIdInOrderByRouteIdAscSequenceNoAsc(Collection<Long> routeIds);
}
