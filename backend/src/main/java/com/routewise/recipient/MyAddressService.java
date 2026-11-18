package com.routewise.recipient;

import java.util.List;

import com.routewise.address.Address;
import com.routewise.address.AddressDtos.CreateAddressRequest;
import com.routewise.address.AddressRepository;
import com.routewise.address.AddressService;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.shipment.ShipmentStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A recipient's own addresses (A4).
 *
 * <p>"Own" is enforced here, not in the security filter, and someone else's
 * address answers 404 — a 403 would confirm that the id exists.
 */
@Service
public class MyAddressService {

    private final RecipientRepository recipients;
    private final AddressRepository addresses;
    private final AddressService addressService;
    private final ShipmentRepository shipments;

    public MyAddressService(RecipientRepository recipients, AddressRepository addresses,
                            AddressService addressService, ShipmentRepository shipments) {
        this.recipients = recipients;
        this.addresses = addresses;
        this.addressService = addressService;
        this.shipments = shipments;
    }

    @Transactional(readOnly = true)
    public Recipient recipientOf(Long userId) {
        // Alıcı rolündeki kullanıcının profili yoksa bu bir veri hatasıdır; yine de
        // varlık sızdırmamak için 404.
        return recipients.findByUserId(userId).orElseThrow(() -> ApiException.notFound("Alıcı profili"));
    }

    @Transactional(readOnly = true)
    public List<Address> list(Long userId) {
        return addresses.findByRecipientIdOrderByIdAsc(recipientOf(userId).getId());
    }

    @Transactional
    public Address create(Long userId, CreateAddressRequest req) {
        return addressService.create(recipientOf(userId).getId(), req);
    }

    /**
     * Moving an address sends it back to the verification queue (see {@link Address#relocate}).
     *
     * <p>Locked while a parcel to it is on the road: the courier is already driving
     * there, and a point that moves under them is exactly the failed delivery the
     * verification flow exists to prevent. Before the courier sets off the move is
     * allowed; the route follows once the company approves it.
     */
    @Transactional
    public Address relocate(Long userId, Long addressId, CreateAddressRequest req) {
        Long recipientId = recipientOf(userId).getId();
        Address address = addresses.findById(addressId)
                .filter(a -> a.getRecipientId().equals(recipientId))
                .orElseThrow(() -> ApiException.notFound("Adres"));
        if (shipments.existsByAddressIdAndStatus(address.getId(), ShipmentStatus.YOLDA)) {
            throw new ApiException(ErrorCode.ADDRESS_LOCKED,
                    "Bu adrese giden kargo yolda; kurye teslim edene kadar adres değiştirilemez.");
        }
        return addressService.relocate(address.getId(), req);
    }
}
