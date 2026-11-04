package com.routewise.recipient;

import java.math.BigDecimal;
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
import com.routewise.optimization.RouteStop;
import com.routewise.optimization.RouteStopRepository;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.shipment.ShipmentStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The recipient's own shipments (A2, A3) with what they need to know: status,
 * planned arrival, and — if the courier could not deliver — why.
 *
 * <p>"Own" is enforced here; someone else's shipment answers 404.
 */
@Service
public class MyShipmentService {

    private final RecipientRepository recipients;
    private final ShipmentRepository shipments;
    private final AddressRepository addresses;
    private final RouteStopRepository stops;

    public MyShipmentService(RecipientRepository recipients, ShipmentRepository shipments,
                             AddressRepository addresses, RouteStopRepository stops) {
        this.recipients = recipients;
        this.shipments = shipments;
        this.addresses = addresses;
        this.stops = stops;
    }

    /**
     * @param eta           planned arrival on the latest dispatched route; null before dispatch
     * @param stopStatus    BEKLEMEDE · TESLIM_EDILDI · ULASILAMADI on that route
     * @param failureReason the courier's note when the stop failed
     */
    public record MyShipment(
            Long id,
            String orderNumber,
            ShipmentStatus status,
            String addressText,
            BigDecimal weightKg,
            Instant createdAt,
            LocalDate postponedTo,
            Instant eta,
            String stopStatus,
            Instant actualArrival,
            String failureReason) {}

    @Transactional(readOnly = true)
    public List<MyShipment> list(Long userId) {
        return view(shipments.findByRecipientIdOrderByCreatedAtDesc(recipientIdOf(userId)));
    }

    @Transactional(readOnly = true)
    public MyShipment get(Long userId, Long shipmentId) {
        Long recipientId = recipientIdOf(userId);
        Shipment s = shipments.findById(shipmentId)
                .filter(x -> x.getRecipientId().equals(recipientId))
                .orElseThrow(() -> ApiException.notFound("Kargo"));
        return view(List.of(s)).get(0);
    }

    private Long recipientIdOf(Long userId) {
        return recipients.findByUserId(userId).map(Recipient::getId)
                .orElseThrow(() -> ApiException.notFound("Alıcı profili"));
    }

    private List<MyShipment> view(List<Shipment> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Address> addressById = addresses.findAllById(list.stream().map(Shipment::getAddressId).distinct().toList())
                .stream().collect(Collectors.toMap(Address::getId, Function.identity()));
        // En yeni sevk edilmiş durak kazanır: ulaşılamayıp ertesi gün yeniden planlanan
        // kargoda alıcı eski günün değil, yeni günün saatini görmeli.
        Map<Long, RouteStop> latestStop = new HashMap<>();
        for (RouteStop rs : stops.findDispatchedForShipments(list.stream().map(Shipment::getId).toList())) {
            latestStop.putIfAbsent(rs.getShipmentId(), rs);
        }
        return list.stream().map(s -> {
            Address a = addressById.get(s.getAddressId());
            RouteStop rs = latestStop.get(s.getId());
            return new MyShipment(s.getId(), s.getOrderNumber(), s.getStatus(),
                    a == null ? null : a.getAddressText(), s.getWeightKg(), s.getCreatedAt(), s.getPostponedTo(),
                    rs == null ? null : rs.getEta(), rs == null ? null : rs.getStatus(),
                    rs == null ? null : rs.getActualArrival(), rs == null ? null : rs.getFailureReason());
        }).toList();
    }
}
