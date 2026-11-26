package com.routewise.address;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AddressDtos {

    private AddressDtos() {}

    public record Coordinate(
            @NotNull(message = "Enlem gerekli.")
            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
            Double lat,

            @NotNull(message = "Boylam gerekli.")
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
            Double lon) {}

    /**
     * Result of the five checks, without saving anything.
     *
     * <p>HTTP stays 200 even when the point is unacceptable: the request was
     * evaluated successfully. {@code acceptable} carries the verdict, and the
     * save attempt is what returns 422.
     */
    public record ValidationResult(
            boolean withinServiceArea,
            Long snappedNodeId,
            Double snapDistanceM,
            String reverseGeocoded,
            Double distanceToNearestM,
            boolean outlier,
            boolean acceptable,
            List<String> warnings,
            List<String> blockers) {}

    public record CreateAddressRequest(
            @NotBlank(message = "Adres metni gerekli.")
            @Size(max = 500)
            String addressText,

            String district,
            String neighbourhood,

            @NotNull(message = "Konum gerekli.")
            Coordinate location) {}

    public record RejectRequest(
            @NotBlank(message = "Ret gerekçesi gerekli.")
            @Size(max = 300, message = "Gerekçe en fazla 300 karakter olabilir.")
            String reason) {}

    public record AddressResponse(
            Long id,
            Long recipientId,
            String recipientName,
            String addressText,
            String district,
            String neighbourhood,
            Double lat,
            Double lon,
            Long snappedNodeId,
            BigDecimal snapDistanceM,
            VerificationStatus verificationStatus,
            String rejectionReason,
            Instant createdAt) {}
}
