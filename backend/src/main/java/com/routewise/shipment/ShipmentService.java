package com.routewise.shipment;

import com.routewise.address.Address;
import com.routewise.address.AddressRepository;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.recipient.RecipientRepository;
import com.routewise.shipment.ShipmentDtos.CreateRequest;
import com.routewise.shipment.ShipmentDtos.UpdateRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShipmentService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

    private final ShipmentRepository shipments;
    private final AddressRepository addresses;
    private final RecipientRepository recipients;
    private final OrderNumberGenerator orderNumbers;

    public ShipmentService(ShipmentRepository shipments, AddressRepository addresses,
                           RecipientRepository recipients, OrderNumberGenerator orderNumbers) {
        this.shipments = shipments;
        this.addresses = addresses;
        this.recipients = recipients;
        this.orderNumbers = orderNumbers;
    }

    @Transactional(readOnly = true)
    public Page<Shipment> list(ShipmentStatus status, String q, Pageable pageable) {
        return shipments.findAll(ShipmentSpecifications.filter(status, normalizeQuery(q)), pageable);
    }

    /** Boş veya yalnızca boşluktan oluşan arama metni filtre sayılmaz. */
    static String normalizeQuery(String q) {
        return (q == null || q.isBlank()) ? null : q.trim();
    }

    @Transactional(readOnly = true)
    public Shipment get(Long id) {
        return shipments.findById(id).orElseThrow(() -> ApiException.notFound("Kargo"));
    }

    @Transactional
    public Shipment create(CreateRequest req) {
        if (!recipients.existsById(req.recipientId())) {
            throw ApiException.notFound("Alıcı");
        }

        Address address = addresses.findById(req.addressId())
                .orElseThrow(() -> ApiException.notFound("Adres"));

        // Adres başka bir alıcıya aitse bu bir veri hatasıdır, yetki sorunu değil.
        if (!address.getRecipientId().equals(req.recipientId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Seçilen adres bu alıcıya ait değil.");
        }

        // Projenin temel kuralı: doğrulanmamış adres rotaya giremez, dolayısıyla
        // kargo da oluşturulamaz.
        if (!address.getVerificationStatus().isUsableForRouting()) {
            throw new ApiException(ErrorCode.ADDRESS_NOT_VERIFIED,
                    "Bu adres henüz doğrulanmadı. Doğrulandıktan sonra kargo oluşturabilirsin.");
        }

        Shipment shipment = new Shipment(
                orderNumbers.next(),
                req.recipientId(),
                req.addressId(),
                req.weightKg(),
                req.priority() == null ? 0 : req.priority(),
                req.serviceTimeMin() == null ? 5 : req.serviceTimeMin());

        Shipment saved = shipments.save(shipment);
        log.info("kargo olusturuldu: {}", saved.getOrderNumber());
        return saved;
    }

    @Transactional
    public Shipment update(Long id, UpdateRequest req) {
        Shipment shipment = get(id);

        if (!shipment.getStatus().isEditable()) {
            throw new ApiException(ErrorCode.ADDRESS_LOCKED,
                    "Kargo yola çıktıktan sonra bilgileri değiştirilemez.");
        }
        shipment.update(req.weightKg(), req.priority(), req.serviceTimeMin());
        return shipment;
    }

    @Transactional
    public void delete(Long id) {
        Shipment shipment = get(id);

        if (!shipment.getStatus().isDeletable()) {
            throw new ApiException(ErrorCode.PLAN_ALREADY_DISPATCHED,
                    "Planlanmış kargo silinemez. Önce planlamadan çıkar.");
        }
        shipments.delete(shipment);
        log.info("kargo silindi: {}", shipment.getOrderNumber());
    }
}
