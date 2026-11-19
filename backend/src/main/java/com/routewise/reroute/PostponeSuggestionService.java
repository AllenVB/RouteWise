package com.routewise.reroute;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.routewise.address.Address;
import com.routewise.address.AddressRepository;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.notification.Notification;
import com.routewise.notification.NotificationService;
import com.routewise.optimization.PlannedRoute;
import com.routewise.optimization.PlannedRouteRepository;
import com.routewise.optimization.RouteStop;
import com.routewise.optimization.RouteStopRepository;
import com.routewise.recipient.Recipient;
import com.routewise.recipient.RecipientRepository;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.vehicle.Vehicle;
import com.routewise.vehicle.VehicleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maliyet artışı eşiği (roadmap phase 5, task 4): the company decides on the
 * postpone suggestions raised by reroutes.
 *
 * <p>Postponing works like the recipient's "Başka gün gelsin": the stop leaves the
 * route in its own transaction, the recipient is told, and the route is
 * re-sequenced afterwards in the background. The courier's K5 screen shows the
 * stop as postponed by the company.
 */
@Service
public class PostponeSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(PostponeSuggestionService.class);

    private final PostponeSuggestionRepository suggestions;
    private final PlannedRouteRepository routes;
    private final RouteStopRepository stops;
    private final ShipmentRepository shipments;
    private final AddressRepository addresses;
    private final VehicleRepository vehicles;
    private final RecipientRepository recipients;
    private final NotificationService notifications;
    private final RerouteScheduler scheduler;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PostponeSuggestionService(PostponeSuggestionRepository suggestions, PlannedRouteRepository routes,
                                     RouteStopRepository stops, ShipmentRepository shipments,
                                     AddressRepository addresses, VehicleRepository vehicles,
                                     RecipientRepository recipients, NotificationService notifications,
                                     RerouteScheduler scheduler, TransactionTemplate tx, Clock clock) {
        this.suggestions = suggestions;
        this.routes = routes;
        this.stops = stops;
        this.shipments = shipments;
        this.addresses = addresses;
        this.vehicles = vehicles;
        this.recipients = recipients;
        this.notifications = notifications;
        this.scheduler = scheduler;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param extraCostTry what the stop adds to the rest of today's round
     * @param extraRatio   that over the round without it (0.25 = 25 %); null if that round costs nothing
     */
    public record Suggestion(Long id, Long routeId, Long stopId, Long shipmentId, String orderNumber,
                             String addressText, String vehiclePlate, BigDecimal costWithoutTry,
                             BigDecimal costWithTry, BigDecimal extraCostTry, BigDecimal extraRatio,
                             PostponeSuggestion.Status status, Instant createdAt) {}

    /** Today's open suggestions whose stop the courier has not closed yet, oldest first. */
    @Transactional(readOnly = true)
    public List<Suggestion> open() {
        Instant dayStart = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        List<PostponeSuggestion> rows = suggestions
                .findByStatusAndCreatedAtGreaterThanEqualOrderByIdAsc(PostponeSuggestion.Status.ACIK, dayStart);
        Map<Long, RouteStop> stopById = stops.findAllById(rows.stream().map(PostponeSuggestion::getStopId).toList())
                .stream().collect(Collectors.toMap(RouteStop::getId, Function.identity()));
        List<PostponeSuggestion> live = rows.stream()
                .filter(r -> stopById.containsKey(r.getStopId()) && stopById.get(r.getStopId()).isOpen())
                .toList();
        return views(live);
    }

    public Suggestion postpone(Long id, Long userId) {
        Decided d = tx.execute(status -> {
            PostponeSuggestion s = lockOpen(id);
            routes.lockById(s.getRouteId()).orElseThrow();
            RouteStop stop = stops.findById(s.getStopId()).orElseThrow();
            if (!stop.isOpen()) {
                throw new ApiException(ErrorCode.STOP_ALREADY_CLOSED,
                        "Kurye bu teslimatı az önce sonuçlandırdı; öneri artık geçerli değil.");
            }
            Shipment shipment = shipments.findById(s.getShipmentId()).orElseThrow();
            LocalDate nextDay = LocalDate.now(clock).plusDays(1);
            stop.postpone();
            shipment.postponeTo(nextDay);
            s.decide(PostponeSuggestion.Status.ERTELENDI, userId, clock.instant());
            recipients.findById(shipment.getRecipientId()).map(Recipient::getUserId).ifPresent(u -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("orderNumber", shipment.getOrderNumber());
                payload.put("postponedTo", nextDay.toString());
                payload.put("reason", RerouteEvent.COMPANY_POSTPONED);
                notifications.notify(u, shipment.getId(), Notification.Type.ERTELEME, payload);
            });
            log.info("oneri {}: firma kargo {} icin {} tarihine erteledi", id, shipment.getId(), nextDay);
            return new Decided(s.getRouteId(), s.getShipmentId());
        });
        // Commit sonrası, arka planda: alıcı ertelemesiyle aynı yol.
        scheduler.request(d.routeId(), RerouteEvent.Trigger.ERTELEME, d.shipmentId());
        return tx.execute(status -> views(List.of(suggestions.findById(id).orElseThrow())).get(0));
    }

    public Suggestion keep(Long id, Long userId) {
        return tx.execute(status -> {
            PostponeSuggestion s = lockOpen(id);
            s.decide(PostponeSuggestion.Status.KALDI, userId, clock.instant());
            log.info("oneri {}: firma kargo {} bugunku rotada kalsin dedi", id, s.getShipmentId());
            return views(List.of(s)).get(0);
        });
    }

    private record Decided(Long routeId, Long shipmentId) {}

    private PostponeSuggestion lockOpen(Long id) {
        PostponeSuggestion s = suggestions.lockById(id).orElseThrow(() -> ApiException.notFound("Erteleme önerisi"));
        if (!s.isOpen()) {
            throw new ApiException(ErrorCode.SUGGESTION_CLOSED, "Bu öneri zaten sonuçlandırıldı.");
        }
        return s;
    }

    private List<Suggestion> views(List<PostponeSuggestion> rows) {
        Map<Long, Shipment> shipmentById = shipments.findAllById(
                        rows.stream().map(PostponeSuggestion::getShipmentId).distinct().toList())
                .stream().collect(Collectors.toMap(Shipment::getId, Function.identity()));
        Map<Long, String> addressText = addresses.findAllById(
                        shipmentById.values().stream().map(Shipment::getAddressId).distinct().toList())
                .stream().collect(Collectors.toMap(Address::getId, Address::getAddressText));
        Map<Long, Long> vehicleOf = routes.findAllById(
                        rows.stream().map(PostponeSuggestion::getRouteId).distinct().toList())
                .stream().collect(Collectors.toMap(PlannedRoute::getId, PlannedRoute::getVehicleId));
        Map<Long, String> plate = vehicles.findAllById(vehicleOf.values().stream().distinct().toList())
                .stream().collect(Collectors.toMap(Vehicle::getId, Vehicle::getPlate));
        return rows.stream().map(r -> {
            Shipment sh = shipmentById.get(r.getShipmentId());
            return new Suggestion(r.getId(), r.getRouteId(), r.getStopId(), r.getShipmentId(),
                    sh == null ? null : sh.getOrderNumber(),
                    sh == null ? null : addressText.get(sh.getAddressId()),
                    plate.get(vehicleOf.get(r.getRouteId())),
                    r.getCostWithoutTry(), r.getCostWithTry(), r.extraCostTry(), r.extraRatio(),
                    r.getStatus(), r.getCreatedAt());
        }).toList();
    }
}
