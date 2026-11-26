package com.routewise.plan;

import java.time.Instant;
import java.util.List;

import com.routewise.optimization.OptimizationRun;
import com.routewise.optimization.OptimizationRunRepository;
import com.routewise.optimization.PlannedRoute;
import com.routewise.optimization.PlannedRouteRepository;
import com.routewise.optimization.RouteStop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Closes a route once no stop is left open, and the plan once every route is closed.
 *
 * <p>One place for the rule: the courier closing the last stop and a reroute
 * that postpones the last open stop must end the day the same way. Callers run
 * inside their own transaction and hold the route's row lock.
 */
@Component
public class RouteCompletion {

    private static final Logger log = LoggerFactory.getLogger(RouteCompletion.class);

    private final OptimizationRunRepository runs;
    private final PlannedRouteRepository routes;
    private final DeliveryPlanRepository plans;

    public RouteCompletion(OptimizationRunRepository runs, PlannedRouteRepository routes,
                           DeliveryPlanRepository plans) {
        this.runs = runs;
        this.routes = routes;
        this.plans = plans;
    }

    /** @return true if the route was completed by this call */
    public boolean completeIfDone(PlannedRoute route, List<RouteStop> stops, Instant now) {
        if ("TAMAMLANDI".equals(route.getStatus()) || stops.stream().anyMatch(RouteStop::isOpen)) {
            return false;
        }
        route.complete(now);
        OptimizationRun run = runs.findById(route.getRunId()).orElseThrow();
        boolean allDone = routes.findByRunIdOrderByIdAsc(run.getId()).stream()
                .allMatch(r -> "TAMAMLANDI".equals(r.getStatus()));
        if (allDone) {
            DeliveryPlan plan = plans.lockById(run.getPlanId()).orElseThrow();
            plan.markCompleted();
            log.info("plan {} tamamlandi", plan.getId());
        }
        return true;
    }
}
