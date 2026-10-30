package com.routewise.recipient;

import com.routewise.address.VerificationStatus;

public final class RecipientDtos {

    private RecipientDtos() {}

    /**
     * An address as the shipment form needs it.
     *
     * <p>{@code usableForRouting} is computed on the server so the form disables
     * unverified addresses with the same rule that makes {@code POST /shipments}
     * reject them — the client never re-implements it.
     */
    public record RecipientAddress(
            Long id,
            String addressText,
            String district,
            String neighbourhood,
            Double lat,
            Double lon,
            VerificationStatus verificationStatus,
            boolean usableForRouting,
            boolean isDefault) {}
}
