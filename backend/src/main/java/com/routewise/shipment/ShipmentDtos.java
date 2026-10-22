package com.routewise.shipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.routewise.address.VerificationStatus;

public final class ShipmentDtos {

    private ShipmentDtos() {}

    /**
     * Shipment creation.
     *
     * <p>No {@code orderNumber} field: the number is produced by the server. A
     * client that sends one is simply ignored, which is why it is absent here
     * rather than validated away.
     */
    public record CreateRequest(
            @NotNull(message = "Alıcı seçilmeli.")
            Long recipientId,

            @NotNull(message = "Teslimat adresi seçilmeli.")
            Long addressId,

            @NotNull(message = "Ağırlık gerekli.")
            @DecimalMin(value = "0.01", message = "Ağırlık sıfırdan büyük olmalı.")
            BigDecimal weightKg,

            @Min(value = 0, message = "Öncelik 0 ile 10 arasında olmalı.")
            @Max(value = 10, message = "Öncelik 0 ile 10 arasında olmalı.")
            Short priority,

            @Min(value = 0, message = "Teslim süresi negatif olamaz.")
            Integer serviceTimeMin) {}

    /** Partial update; null fields are left untouched. */
    public record UpdateRequest(
            @DecimalMin(value = "0.01", message = "Ağırlık sıfırdan büyük olmalı.")
            BigDecimal weightKg,

            @Min(value = 0, message = "Öncelik 0 ile 10 arasında olmalı.")
            @Max(value = 10, message = "Öncelik 0 ile 10 arasında olmalı.")
            Short priority,

            @Min(value = 0, message = "Teslim süresi negatif olamaz.")
            Integer serviceTimeMin) {}

    public record ShipmentResponse(
            Long id,
            String orderNumber,
            RecipientSummary recipient,
            AddressSummary address,
            BigDecimal weightKg,
            short priority,
            int serviceTimeMin,
            ShipmentStatus status,
            LocalDate postponedTo,
            Instant createdAt) {}

    public record RecipientSummary(Long id, String fullName) {}

    public record AddressSummary(Long id, String text, VerificationStatus verificationStatus) {}
}
