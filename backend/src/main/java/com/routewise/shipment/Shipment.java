package com.routewise.shipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A parcel to be delivered. Schema owned by Liquibase. */
@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Server-generated, never supplied by the client. */
    @Column(name = "order_number", nullable = false, unique = true, updatable = false)
    private String orderNumber;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Column(name = "address_id", nullable = false)
    private Long addressId;

    @Column(name = "weight_kg", nullable = false)
    private BigDecimal weightKg;

    @Column(name = "volume_m3")
    private BigDecimal volumeM3;

    @Column(nullable = false)
    private short priority;

    @Column(name = "service_time_min", nullable = false)
    private int serviceTimeMin = 5;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status = ShipmentStatus.BEKLEMEDE;

    @Column(name = "postponed_to")
    private LocalDate postponedTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Shipment() {}

    public Shipment(String orderNumber, Long recipientId, Long addressId,
                    BigDecimal weightKg, short priority, int serviceTimeMin) {
        this.orderNumber = orderNumber;
        this.recipientId = recipientId;
        this.addressId = addressId;
        this.weightKg = weightKg;
        this.priority = priority;
        this.serviceTimeMin = serviceTimeMin;
    }

    public Long getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public Long getRecipientId() { return recipientId; }
    public Long getAddressId() { return addressId; }
    public BigDecimal getWeightKg() { return weightKg; }
    public BigDecimal getVolumeM3() { return volumeM3; }
    public short getPriority() { return priority; }
    public int getServiceTimeMin() { return serviceTimeMin; }
    public ShipmentStatus getStatus() { return status; }
    public LocalDate getPostponedTo() { return postponedTo; }
    public Instant getCreatedAt() { return createdAt; }

    /** Partial update of the fields the company may change while planning. */
    public void update(BigDecimal weightKg, Short priority, Integer serviceTimeMin) {
        if (weightKg != null) {
            this.weightKg = weightKg;
        }
        if (priority != null) {
            this.priority = priority;
        }
        if (serviceTimeMin != null) {
            this.serviceTimeMin = serviceTimeMin;
        }
        this.updatedAt = Instant.now();
    }

    public void postponeTo(LocalDate date) {
        this.status = ShipmentStatus.ERTELENDI;
        this.postponedTo = date;
        this.updatedAt = Instant.now();
    }
}
