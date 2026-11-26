package com.routewise.recipient;

/** A recipient as the shipment form lists it: profile joined with the user row. */
public record RecipientSummary(
        Long id,
        String fullName,
        String email,
        String phone,
        Long defaultAddressId) {}
