package com.routewise.recipient;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.RouteStop;
import com.routewise.optimization.RouteStopRepository;
import com.routewise.optimization.PlannedRouteRepository;
import com.routewise.recipient.MyShipmentService.MyShipment;
import com.routewise.reroute.RerouteEvent;
import com.routewise.reroute.RerouteScheduler;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.shipment.ShipmentStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Başka gün gelsin" (A3): the recipient moves their own shipment to another day.
 *
 * <p>If the shipment is on a route a courier is driving, its stop leaves the route
 * and the route is re-sequenced from the courier's last closed stop, in the
 * background ({@link RerouteScheduler}). The
 * postponement commits <b>first</b> and on its own: it is the recipient's
 * decision and stands even if the optimizer is down; the courier then still sees
 * the stop removed, only the ETAs of the rest are not refreshed.
 */
@Service
public class PostponeService {

    private static final Logger log = LoggerFactory.getLogger(PostponeService.class);

    /** Further out is a new order, not a postponement; the parcel would sit in the depot for weeks. */
    static final int MAX_DAYS_AHEAD = 14;

    private final RecipientRepository recipients;
    private final ShipmentRepository shipments;
    private final RouteStopRepository stops;
    private final PlannedRouteRepository routes;
    private final MyShipmentService myShipments;
    private final RerouteScheduler reroute;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PostponeService(RecipientRepository recipients, ShipmentRepository shipments,
                           RouteStopRepository stops, PlannedRouteRepository routes,
                           MyShipmentService myShipments, RerouteScheduler reroute,
                           TransactionTemplate tx, Clock clock) {
        this.recipients = recipients;
        this.shipments = shipments;
        this.stops = stops;
        this.routes = routes;
        this.myShipments = myShipments;
        this.reroute = reroute;
        this.tx = tx;
        this.clock = clock;
    }

    public MyShipment postpone(Long userId, Long shipmentId, LocalDate date) {
        Long routeId = tx.execute(status -> mark(userId, shipmentId, date));
        if (routeId != null) {
            // Commit sonrası, arka planda: alıcı optimizasyonu beklemez. Hesaplama
            // başarısız olsa bile erteleme kayıtlı ve durak kuryenin listesinden düştü.
            reroute.request(routeId, RerouteEvent.Trigger.ERTELEME, shipmentId);
        }
        return myShipments.get(userId, shipmentId);
    }

    /** @return the dispatched route the shipment left, or null if it was not on one */
    private Long mark(Long userId, Long shipmentId, LocalDate date) {
        Long recipientId = recipients.findByUserId(userId).map(Recipient::getId)
                .orElseThrow(() -> ApiException.notFound("Alıcı profili"));
        Shipment shipment = shipments.findById(shipmentId)
                .filter(s -> s.getRecipientId().equals(recipientId))
                .orElseThrow(() -> ApiException.notFound("Kargo"));
        if (shipment.getStatus() == ShipmentStatus.TESLIM_EDILDI) {
            throw new ApiException(ErrorCode.SHIPMENT_NOT_POSTPONABLE, "Teslim edilmiş kargo ertelenemez.");
        }
        LocalDate today = LocalDate.now(clock);
        if (date == null || !date.isAfter(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Erteleme tarihi yarından en fazla " + MAX_DAYS_AHEAD + " gün sonrasına kadar olabilir.");
        }

        Long routeId = null;
        List<Long> open = stops.findOpenDispatchedRouteIds(shipmentId);
        if (!open.isEmpty()) {
            // Kuryenin durak kapatmasıyla yarışmasın: rota kilidi alınır, durak ondan sonra okunur.
            routeId = open.get(0);
            routes.lockById(routeId).orElseThrow();
            RouteStop stop = stops.findByRouteIdOrderBySequenceNoAsc(routeId).stream()
                    .filter(s -> s.getShipmentId().equals(shipmentId) && s.isOpen())
                    .findFirst().orElse(null);
            if (stop == null) {
                // Kilidi beklerken kurye durağı kapattı.
                throw new ApiException(ErrorCode.SHIPMENT_NOT_POSTPONABLE,
                        "Kurye bu teslimatı az önce sonuçlandırdı; kargo durumunu yenileyin.");
            }
            stop.postpone();
        }
        shipment.postponeTo(date);
        log.info("kargo {} aliciya gore {} tarihine ertelendi{}", shipmentId, date,
                routeId == null ? "" : " (rota " + routeId + " yeniden hesaplanacak)");
        return routeId;
    }
}
