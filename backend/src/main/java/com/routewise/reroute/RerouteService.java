package com.routewise.reroute;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.routewise.address.Address;
import com.routewise.address.AddressRepository;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.cost.CostParameters;
import com.routewise.cost.CostParametersRepository;
import com.routewise.courier.CourierRepository;
import com.routewise.depot.Depot;
import com.routewise.depot.DepotService;
import com.routewise.notification.Notification;
import com.routewise.notification.NotificationService;
import com.routewise.optimization.Algorithm;
import com.routewise.optimization.OptimizationRun;
import com.routewise.optimization.OptimizationRunRepository;
import com.routewise.optimization.PlannedRoute;
import com.routewise.optimization.PlannedRouteRepository;
import com.routewise.optimization.RouteStop;
import com.routewise.optimization.RouteStopRepository;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.CostParamsIn;
import com.routewise.optimization.client.OptimizerDtos.MatrixIn;
import com.routewise.optimization.client.OptimizerDtos.OptimizeRequest;
import com.routewise.optimization.client.OptimizerDtos.OptimizeResult;
import com.routewise.optimization.client.OptimizerDtos.RouteOut;
import com.routewise.optimization.client.OptimizerDtos.StopIn;
import com.routewise.optimization.client.OptimizerDtos.StopOut;
import com.routewise.optimization.client.OptimizerDtos.UnassignedOut;
import com.routewise.optimization.client.OptimizerDtos.VehicleIn;
import com.routewise.optimization.client.OptimizerDtos.WarmRouteIn;
import com.routewise.plan.DeliveryPlan;
import com.routewise.plan.DeliveryPlanRepository;
import com.routewise.plan.PlanStatus;
import com.routewise.plan.RouteCompletion;
import com.routewise.recipient.Recipient;
import com.routewise.recipient.RecipientRepository;
import com.routewise.routing.matrix.DistanceMatrix;
import com.routewise.routing.matrix.DistanceMatrixProvider;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.vehicle.Vehicle;
import com.routewise.vehicle.VehicleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-sequences one dispatched route after it changed under the courier
 * (architecture §12, roadmap phase 5).
 *
 * <p>Only <b>that</b> route: its parcels are physically in that vehicle, so moving
 * one to another courier is not a routing decision. Visited stops stay where
 * they are; the open ones are optimized again from the courier's last closed
 * stop (no live GPS) back to the depot, within what is left of the workday.
 * A stop that no longer fits is postponed to the next day automatically and
 * its recipient is told — the route never silently overruns the day.
 *
 * <p>Same shape as {@code OptimizationService}: read, call the optimizer with
 * no transaction open, then write under the route's row lock. If the open
 * stops changed in between (the courier closed one, another change landed),
 * the result answers a question nobody asks any more and is computed again.
 */
@Service
public class RerouteService {

    private static final Logger log = LoggerFactory.getLogger(RerouteService.class);

    static final int MAX_ATTEMPTS = 3;
    /** A smaller ETA shift is noise; telling the recipient would train them to ignore it. */
    static final Duration ETA_NOTIFY_THRESHOLD = Duration.ofMinutes(15);
    static final String EXCEEDS_WORKDAY = "EXCEEDS_WORKDAY";
    static final String NO_ROAD_NODE = "NO_ROAD_NODE";

    private final PlannedRouteRepository routes;
    private final RouteStopRepository stops;
    private final OptimizationRunRepository runs;
    private final DeliveryPlanRepository plans;
    private final ShipmentRepository shipments;
    private final AddressRepository addresses;
    private final VehicleRepository vehicles;
    private final CostParametersRepository costs;
    private final CourierRepository couriers;
    private final RecipientRepository recipients;
    private final DepotService depots;
    private final DistanceMatrixProvider matrices;
    private final OptimizerClient optimizer;
    private final NotificationService notifications;
    private final RerouteEventRepository events;
    private final RouteCompletion completion;
    private final RerouteProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RerouteService(PlannedRouteRepository routes, RouteStopRepository stops,
                          OptimizationRunRepository runs, DeliveryPlanRepository plans,
                          ShipmentRepository shipments, AddressRepository addresses,
                          VehicleRepository vehicles, CostParametersRepository costs,
                          CourierRepository couriers, RecipientRepository recipients,
                          DepotService depots, DistanceMatrixProvider matrices, OptimizerClient optimizer,
                          NotificationService notifications, RerouteEventRepository events,
                          RouteCompletion completion, RerouteProperties props, TransactionTemplate tx,
                          Clock clock) {
        this.routes = routes;
        this.stops = stops;
        this.runs = runs;
        this.plans = plans;
        this.shipments = shipments;
        this.addresses = addresses;
        this.vehicles = vehicles;
        this.costs = costs;
        this.couriers = couriers;
        this.recipients = recipients;
        this.depots = depots;
        this.matrices = matrices;
        this.optimizer = optimizer;
        this.notifications = notifications;
        this.events = events;
        this.completion = completion;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param rerouted      false when there was nothing to do (route closed, plan not on the road)
     * @param openStops     stops left for the courier after the reroute
     * @param autoPostponed order numbers moved to the next day because they no longer fit
     */
    public record Outcome(Long routeId, boolean rerouted, int openStops, List<String> autoPostponed) {

        static Outcome skipped(Long routeId) {
            return new Outcome(routeId, false, 0, List.of());
        }
    }

    public Outcome reroute(Long routeId, RerouteEvent.Trigger trigger, Long shipmentId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Snapshot snap = tx.execute(status -> snapshot(routeId));
            if (snap == null) {
                return Outcome.skipped(routeId);
            }
            if (!snap.open().isEmpty() && limitReached(routeId)) {
                return tx.execute(status -> keepOrder(snap, trigger, shipmentId));
            }
            Solved solved = solve(snap);
            Outcome out = tx.execute(status -> persist(snap, solved, trigger, shipmentId));
            if (out != null) {
                return out;
            }
            log.info("rota {}: hesaplama sirasinda duraklar degisti, yeniden ({}/{})", routeId, attempt, MAX_ATTEMPTS);
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR,
                "Rota hesaplanırken duraklar sürekli değişti; değişiklik kaydedildi, rota bir sonraki değişiklikte güncellenecek.");
    }

    /** Günlük değişiklik limiti: bugün (depo saatiyle) bu rotada yapılmış yeniden rotalama sayısı. */
    private boolean limitReached(Long routeId) {
        Instant dayStart = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        return events.countByRouteIdAndCreatedAtGreaterThanEqual(routeId, dayStart) >= props.maxPerRoutePerDay();
    }

    /**
     * Limit dolduysa kuryenin sırası artık değişmez: çıkan durak çıkmış olarak kalır
     * (çağıran işaretledi), kalanlar eski sırasıyla sürer. Kurye yine bilgilendirilir.
     */
    private Outcome keepOrder(Snapshot snap, RerouteEvent.Trigger trigger, Long triggerShipmentId) {
        PlannedRoute route = routes.lockById(snap.routeId()).orElseThrow();
        Shipment cause = triggerShipmentId == null ? null : shipments.findById(triggerShipmentId).orElse(null);
        notifyCourier(route, trigger, cause, snap.open().size(), List.of(), true);
        log.info("rota {}: gunluk yeniden rotalama limiti ({}) dolu, sira korunuyor", route.getId(),
                props.maxPerRoutePerDay());
        return new Outcome(route.getId(), false, snap.open().size(), List.of());
    }

    // --- 1. okuma -------------------------------------------------------------

    /** An open stop as the optimizer needs it; plain values, no entity crosses the transaction. */
    record OpenStop(Long stopId, Long shipmentId, Long nodeId, double demandKg, double serviceS, int priority) {}

    /**
     * @param start  where the courier is: the last visited stop's node, or the depot
     * @param base   when the courier leaves {@code start}: now, or the planned departure if still ahead
     * @param limitS what is left of the workday; null once it is over (finish the round, do not drop it)
     */
    record Snapshot(Long routeId, LocalDate planDate, long depotNode, long start, Instant base, Double limitS,
                    Vehicle vehicle, CostParameters price, List<OpenStop> open) {

        Set<Long> openStopIds() {
            return open.stream().map(OpenStop::stopId).collect(Collectors.toSet());
        }
    }

    private Snapshot snapshot(Long routeId) {
        PlannedRoute route = routes.findById(routeId).orElse(null);
        if (route == null || "TAMAMLANDI".equals(route.getStatus())) {
            return null;
        }
        OptimizationRun run = runs.findById(route.getRunId()).orElseThrow();
        DeliveryPlan plan = plans.findById(run.getPlanId()).orElseThrow();
        if (plan.getStatus() != PlanStatus.SEVK_EDILDI || !run.isApplied()) {
            return null;
        }
        Depot depot = depots.primary();
        List<RouteStop> all = stops.findByRouteIdOrderBySequenceNoAsc(routeId);
        List<RouteStop> open = all.stream().filter(RouteStop::isOpen).toList();
        Map<Long, Shipment> byId = shipments.findAllById(open.stream().map(RouteStop::getShipmentId).toList())
                .stream().collect(Collectors.toMap(Shipment::getId, Function.identity()));
        Map<Long, Long> addressNode = addresses.findAllById(
                        byId.values().stream().map(Shipment::getAddressId).distinct().toList()).stream()
                .filter(a -> a.getSnappedNodeId() != null)
                .collect(Collectors.toMap(Address::getId, Address::getSnappedNodeId));

        long start = all.stream().filter(RouteStop::isVisited).filter(s -> s.getNodeId() != null)
                .max(Comparator.comparing(RouteStop::getActualArrival, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(RouteStop::getSequenceNo))
                .map(RouteStop::getNodeId)
                .orElse(depot.getSnappedNodeId());

        Instant departure = ZonedDateTime.of(plan.getPlanDate(), depot.getOpeningTime(), clock.getZone()).toInstant();
        Instant now = clock.instant();
        Instant base = now.isAfter(departure) ? now : departure;
        long left = Duration.between(base, departure.plus(Duration.ofMinutes(depot.getMaxRouteDurationMin())))
                .toSeconds();

        List<OpenStop> openStops = open.stream().map(s -> {
            Shipment sh = byId.get(s.getShipmentId());
            // Durağın sevkteki düğümü esastır; eski kayıtta yoksa adresinkine düşülür.
            Long node = s.getNodeId() != null ? s.getNodeId() : addressNode.get(sh.getAddressId());
            return new OpenStop(s.getId(), sh.getId(), node, sh.getWeightKg().doubleValue(),
                    sh.getServiceTimeMin() * 60.0, sh.getPriority());
        }).toList();

        return new Snapshot(routeId, plan.getPlanDate(), depot.getSnappedNodeId(), start, base,
                left > 0 ? (double) left : null,
                vehicles.findById(route.getVehicleId()).orElseThrow(),
                costs.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(plan.getPlanDate())
                        .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                                "Plan tarihi için tanımlı maliyet parametresi yok.")),
                openStops);
    }

    // --- 2. hesaplama (transaction dışında) -----------------------------------

    /**
     * @param order    new visiting order of the stops that still fit
     * @param dropped  shipment id -> why it leaves the route
     * @param keptCost the old order with removed stops skipped; null if even that does not fit the day
     */
    record Solved(List<StopOut> order, Map<Long, String> dropped, BigDecimal keptCost, BigDecimal newCost,
                  Integer computationMs) {}

    private Solved solve(Snapshot snap) {
        Map<Long, String> dropped = new LinkedHashMap<>();
        List<OpenStop> candidates = new ArrayList<>();
        for (OpenStop s : snap.open()) {
            if (s.nodeId() == null) {
                dropped.put(s.shipmentId(), NO_ROAD_NODE);
            } else {
                candidates.add(s);
            }
        }

        while (!candidates.isEmpty()) {
            OptimizeResult result;
            try {
                result = optimizer.optimize(request(snap, candidates));
            } catch (ApiException e) {
                if (e.code() != ErrorCode.NO_FEASIBLE_SOLUTION) {
                    throw e;
                }
                // Kalan güne birlikte sığmıyorlar. Önceliği en düşük, eşitse sırada
                // en sonda olan çıkar: kuryenin zaten en son uğrayacağı yer.
                OpenStop victim = candidates.stream()
                        .min(Comparator.comparingInt(OpenStop::priority)
                                .thenComparing(Comparator.comparingInt(candidates::indexOf).reversed()))
                        .orElseThrow();
                candidates.remove(victim);
                dropped.put(victim.shipmentId(), EXCEEDS_WORKDAY);
                continue;
            }
            for (UnassignedOut u : result.unassigned()) {
                dropped.put(u.stopId(), u.reason());
            }
            List<StopOut> order = result.result().routes().stream()
                    .filter(r -> r.vehicleId() == snap.vehicle().getId())
                    .findFirst().map(RouteOut::stops).orElse(List.of());
            return new Solved(order, dropped,
                    result.baseline() == null ? null : money(result.baseline().totalCostTry()),
                    money(result.result().totalCostTry()),
                    (int) Math.ceil(result.result().computationTimeMs()));
        }
        return new Solved(List.of(), dropped, null, null, null);
    }

    /** Current order goes first: it is the naive baseline and the warm start, so an unchanged order stays. */
    private OptimizeRequest request(Snapshot snap, List<OpenStop> candidates) {
        List<Long> nodes = new ArrayList<>();
        nodes.add(snap.depotNode());
        nodes.add(snap.start());
        candidates.forEach(s -> nodes.add(s.nodeId()));
        DistanceMatrix m = matrices.forNodes(nodes.stream().distinct().toList());
        Vehicle v = snap.vehicle();
        CostParameters p = snap.price();
        return new OptimizeRequest(
                Algorithm.ROUTEWISE.name(),
                snap.depotNode(),
                candidates.stream().map(s -> new StopIn(s.shipmentId(), s.nodeId(), s.demandKg(), s.serviceS(),
                        s.priority())).toList(),
                List.of(new VehicleIn(v.getId(), v.getCapacityKg().doubleValue(),
                        v.getFuelLPer100km().doubleValue(), v.getFixedCostPerDay().doubleValue())),
                new MatrixIn(m.nodeIds(), m.distanceM(), m.durationS()),
                new CostParamsIn(p.getFuelPricePerL().doubleValue(), p.getDriverCostPerHour().doubleValue(),
                        p.getPriorityPenalty().doubleValue()),
                snap.routeId(),     // aynı rota, aynı girdi -> aynı sıra
                List.of(new WarmRouteIn(v.getId(), candidates.stream().map(OpenStop::shipmentId).toList())),
                snap.limitS(),
                snap.start() == snap.depotNode() ? null : snap.start());
    }

    // --- 3. yazma (rota kilidi altında) ---------------------------------------

    /** @return null if the route changed since the snapshot and the result is stale */
    private Outcome persist(Snapshot snap, Solved solved, RerouteEvent.Trigger trigger, Long triggerShipmentId) {
        PlannedRoute route = routes.lockById(snap.routeId()).orElseThrow();
        List<RouteStop> all = stops.findByRouteIdOrderBySequenceNoAsc(route.getId());
        Set<Long> openNow = all.stream().filter(RouteStop::isOpen).map(RouteStop::getId).collect(Collectors.toSet());
        if (!openNow.equals(snap.openStopIds())) {
            return null;
        }
        Instant now = clock.instant();
        Map<Long, RouteStop> stopOf = all.stream().filter(RouteStop::isOpen)
                .collect(Collectors.toMap(RouteStop::getShipmentId, Function.identity()));
        Map<Long, Shipment> byId = shipments.findAllById(all.stream().map(RouteStop::getShipmentId).toList())
                .stream().collect(Collectors.toMap(Shipment::getId, Function.identity()));
        Map<Long, Long> userOf = recipientUsers(byId.values());

        int seq = 0;
        // Uğranan duraklar uğrandıkları sırayla başa: kurye sırayı bozmuş olabilir.
        for (RouteStop s : all.stream().filter(RouteStop::isVisited)
                .sorted(Comparator.comparing(RouteStop::getActualArrival, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(RouteStop::getSequenceNo)).toList()) {
            s.renumber(++seq);
        }
        for (StopOut so : solved.order()) {
            RouteStop s = stopOf.get(so.stopId());
            Instant before = s.getEta();
            Instant eta = snap.base().plusSeconds(Math.round(so.arrivalS()));
            s.reschedule(++seq, meters(so.distanceFromPrevM()), (int) Math.round(so.durationFromPrevS()), eta);
            if (before != null && Duration.between(before, eta).abs().compareTo(ETA_NOTIFY_THRESHOLD) >= 0) {
                Shipment sh = byId.get(s.getShipmentId());
                notifyRecipient(userOf, sh, Notification.Type.ETA, Map.of(
                        "orderNumber", sh.getOrderNumber(), "planDate", snap.planDate().toString(),
                        "eta", eta.toString(), "updated", true));
            }
        }
        List<String> autoPostponed = new ArrayList<>();
        LocalDate nextDay = snap.planDate().plusDays(1);
        solved.dropped().forEach((shipmentId, reason) -> {
            RouteStop s = stopOf.get(shipmentId);
            Shipment sh = byId.get(shipmentId);
            s.postpone();
            sh.postponeTo(nextDay);
            autoPostponed.add(sh.getOrderNumber());
            notifyRecipient(userOf, sh, Notification.Type.ERTELEME, Map.of(
                    "orderNumber", sh.getOrderNumber(), "postponedTo", nextDay.toString(), "reason", reason));
        });
        for (RouteStop s : all.stream().filter(RouteStop::isPostponed)
                .sorted(Comparator.comparing(RouteStop::getSequenceNo)).toList()) {
            s.renumber(++seq);
        }

        int openAfter = solved.order().size();
        completion.completeIfDone(route, all, now);
        notifyCourier(route, trigger, byId.get(triggerShipmentId), openAfter, autoPostponed, false);
        events.save(new RerouteEvent(route.getId(), trigger, triggerShipmentId, snap.start(),
                snap.open().size(), openAfter, autoPostponed.size(),
                solved.keptCost(), solved.newCost(), solved.computationMs(), now));

        log.info("rota {} yeniden rotalandi ({}): {} -> {} acik durak, {} otomatik ertelendi, maliyet {} -> {} TL",
                route.getId(), trigger, snap.open().size(), openAfter, autoPostponed.size(),
                solved.keptCost(), solved.newCost());
        return new Outcome(route.getId(), true, openAfter, autoPostponed);
    }

    /** @param orderKept the daily limit was reached: the stop left but the order did not change */
    private void notifyCourier(PlannedRoute route, RerouteEvent.Trigger trigger, Shipment cause, int openStops,
                               List<String> autoPostponed, boolean orderKept) {
        if (route.getCourierId() == null) {
            return;
        }
        couriers.findById(route.getCourierId()).ifPresent(c -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("routeId", route.getId());
            payload.put("trigger", trigger.name());
            payload.put("orderNumber", cause == null ? null : cause.getOrderNumber());
            payload.put("openStops", openStops);
            payload.put("autoPostponed", autoPostponed);
            payload.put("orderKept", orderKept);
            notifications.notify(c.getUserId(), cause == null ? null : cause.getId(),
                    Notification.Type.ROTA_DEGISTI, payload);
        });
    }

    private void notifyRecipient(Map<Long, Long> userOf, Shipment s, Notification.Type type,
                                 Map<String, Object> payload) {
        Long userId = userOf.get(s.getRecipientId());
        if (userId != null) {
            notifications.notify(userId, s.getId(), type, new HashMap<>(payload));
        }
    }

    private Map<Long, Long> recipientUsers(Iterable<Shipment> list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(s -> ids.add(s.getRecipientId()));
        return recipients.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Recipient::getId, Recipient::getUserId));
    }

    private static BigDecimal meters(double m) {
        return BigDecimal.valueOf(m).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(Double tl) {
        return tl == null ? null : BigDecimal.valueOf(tl).setScale(2, RoundingMode.HALF_UP);
    }
}
