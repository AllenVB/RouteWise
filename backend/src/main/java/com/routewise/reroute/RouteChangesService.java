package com.routewise.reroute;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.routewise.common.error.ApiException;
import com.routewise.optimization.PlannedRoute;
import com.routewise.optimization.PlannedRouteRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * K5 "Rota değişti": what changed on the courier's route during the day, newest last.
 *
 * <p>Read from {@code reroute_events}, not from notifications: the inbox can be
 * marked read or carry other types, the events are the route's own history.
 * {@code recomputing} is the progress indicator — a change is in and its reroute
 * has not finished, so the order on screen is about to change.
 */
@Service
public class RouteChangesService {

    private final PlannedRouteRepository routes;
    private final RerouteEventRepository events;
    private final RerouteScheduler scheduler;

    public RouteChangesService(PlannedRouteRepository routes, RerouteEventRepository events,
                               RerouteScheduler scheduler) {
        this.routes = routes;
        this.events = events;
        this.scheduler = scheduler;
    }

    /**
     * @param orderKept the daily limit was reached: removals happened, the order did not change
     * @param openStops stops left for the courier after this change
     */
    public record RouteChange(Long id, RerouteEvent.Trigger trigger, Instant at, boolean orderKept, int openStops,
                              List<RerouteEvent.Removed> removed, List<RerouteEvent.Moved> moved,
                              List<RerouteEvent.Reordered> reordered) {}

    public record RouteChanges(Long routeId, boolean recomputing, List<RouteChange> changes) {}

    /** @param since only changes after this instant (the screen's last poll); null for the whole day */
    @Transactional(readOnly = true)
    public RouteChanges forCourier(Long courierId, LocalDate date, Instant since) {
        PlannedRoute route = routes.findDispatchedFor(courierId, date)
                .orElseThrow(() -> ApiException.notFound("Bu gün için sevk edilmiş rota"));
        List<RerouteEvent> rows = since == null
                ? events.findByRouteIdOrderByIdAsc(route.getId())
                : events.findByRouteIdAndCreatedAtAfterOrderByIdAsc(route.getId(), since);
        return new RouteChanges(route.getId(), scheduler.isBusy(route.getId()), rows.stream()
                .map(e -> new RouteChange(e.getId(), e.getTrigger(), e.getCreatedAt(), e.isOrderKept(),
                        e.getOpenStopsAfter(), e.getChanges().removed(), e.getChanges().moved(),
                        e.getChanges().reordered()))
                .toList());
    }
}
