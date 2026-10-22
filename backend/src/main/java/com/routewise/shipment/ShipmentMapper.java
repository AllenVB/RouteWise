package com.routewise.shipment;

import com.routewise.address.AddressRepository;
import com.routewise.shipment.ShipmentDtos.AddressSummary;
import com.routewise.shipment.ShipmentDtos.RecipientSummary;
import com.routewise.shipment.ShipmentDtos.ShipmentResponse;
import com.routewise.recipient.RecipientRepository;
import com.routewise.user.UserRepository;

import org.springframework.stereotype.Component;

/**
 * Builds the response shape the shipment screens need.
 *
 * <p>Screens show the recipient's name and the address text, not raw ids, so the
 * mapper resolves them here instead of making the frontend issue extra calls.
 */
@Component
public class ShipmentMapper {

    private final AddressRepository addresses;
    private final RecipientRepository recipients;
    private final UserRepository users;

    public ShipmentMapper(AddressRepository addresses, RecipientRepository recipients,
                          UserRepository users) {
        this.addresses = addresses;
        this.recipients = recipients;
        this.users = users;
    }

    public ShipmentResponse toResponse(Shipment s) {
        return new ShipmentResponse(
                s.getId(),
                s.getOrderNumber(),
                recipientSummary(s.getRecipientId()),
                addressSummary(s.getAddressId()),
                s.getWeightKg(),
                s.getPriority(),
                s.getServiceTimeMin(),
                s.getStatus(),
                s.getPostponedTo(),
                s.getCreatedAt());
    }

    private RecipientSummary recipientSummary(Long recipientId) {
        return recipients.findById(recipientId)
                .flatMap(r -> users.findById(r.getUserId())
                        .map(u -> new RecipientSummary(recipientId, u.getFullName())))
                .orElseGet(() -> new RecipientSummary(recipientId, null));
    }

    private AddressSummary addressSummary(Long addressId) {
        return addresses.findById(addressId)
                .map(a -> new AddressSummary(a.getId(), a.getAddressText(),
                        a.getVerificationStatus()))
                .orElseGet(() -> new AddressSummary(addressId, null, null));
    }
}
