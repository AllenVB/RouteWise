package com.routewise.reroute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.routewise.address.AddressService;
import com.routewise.optimization.PlannedRouteRepository;
import com.routewise.optimization.RouteStop;
import com.routewise.optimization.RouteStopRepository;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * An address moved during delivery (roadmap phase 5, task 2): the route follows
 * only once the company has decided.
 *
 * <ul>
 *   <li><b>Approved:</b> the open stop moves to the new road node and its route
 *       is rerouted.</li>
 *   <li><b>Rejected:</b> the parcel cannot be delivered there today. The stop
 *       leaves the route and the shipment goes back to the pool, where it stays
 *       out of planning until the recipient fixes the address.</li>
 * </ul>
 *
 * <p>Runs inside the verifying transaction, so stop changes commit with the
 * decision; the reroute itself is requested for after the commit.
 */
@Component
public class AddressRerouteHandler {

    private static final Logger log = LoggerFactory.getLogger(AddressRerouteHandler.class);

    private final RouteStopRepository stops;
    private final PlannedRouteRepository routes;
    private final ShipmentRepository shipments;
    private final RerouteScheduler scheduler;

    public AddressRerouteHandler(RouteStopRepository stops, PlannedRouteRepository routes,
                                 ShipmentRepository shipments, RerouteScheduler scheduler) {
        this.stops = stops;
        this.routes = routes;
        this.shipments = shipments;
        this.scheduler = scheduler;
    }

    @EventListener
    public void onVerified(AddressService.Verified e) {
        forEachOpenStop(e.addressId(), (stop, shipment) -> {
            if (e.snappedNodeId() == null || Objects.equals(stop.getNodeId(), e.snappedNodeId())) {
                return false;   // yalnızca metin değişti ya da nokta aynı düğüme düşüyor
            }
            stop.moveTo(e.snappedNodeId());
            return true;
        });
    }

    @EventListener
    public void onRejected(AddressService.Rejected e) {
        forEachOpenStop(e.addressId(), (stop, shipment) -> {
            stop.postpone();
            shipment.returnToPool();
            return true;
        });
    }

    @FunctionalInterface
    private interface StopChange {
        /** @return true if the route must be recomputed */
        boolean apply(RouteStop stop, Shipment shipment);
    }

    private void forEachOpenStop(Long addressId, StopChange change) {
        Map<Long, List<Long>> byRoute = new LinkedHashMap<>();
        for (Object[] row : stops.findOpenDispatchedByAddress(addressId)) {
            byRoute.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((Long) row[1]);
        }
        // Rota kimliği sırasıyla kilitlenir: iki eşzamanlı karar birbirini beklemez, kilitlenmez.
        byRoute.forEach((routeId, shipmentIds) -> {
            routes.lockById(routeId).orElseThrow();
            Long changed = null;
            for (RouteStop stop : stops.findByRouteIdOrderBySequenceNoAsc(routeId)) {
                if (!stop.isOpen() || !shipmentIds.contains(stop.getShipmentId())) {
                    continue;   // kilidi beklerken kurye kapattı
                }
                Optional<Shipment> shipment = shipments.findById(stop.getShipmentId());
                if (shipment.isPresent() && change.apply(stop, shipment.get())) {
                    changed = stop.getShipmentId();
                }
            }
            if (changed != null) {
                log.info("adres {} karari rota {} durağını degistirdi, yeniden rotalama istendi", addressId, routeId);
                scheduler.request(routeId, RerouteEvent.Trigger.ADRES_DEGISIKLIGI, changed);
            }
        });
    }
}
