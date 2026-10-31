package com.routewise.plan;

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

/** One depot's delivery day. Schema owned by Liquibase; unique per (depot, date). */
@Entity
@Table(name = "delivery_plans")
public class DeliveryPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "depot_id", nullable = false)
    private Long depotId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status = PlanStatus.TASLAK;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DeliveryPlan() {}

    public DeliveryPlan(Long depotId, LocalDate planDate, Long createdBy) {
        this.depotId = depotId;
        this.planDate = planDate;
        this.createdBy = createdBy;
    }

    /** Uygulanan koşu kuryelere verildi; plan artık yeniden optimize edilemez. */
    public void markDispatched() {
        this.status = PlanStatus.SEVK_EDILDI;
    }

    public Long getId() { return id; }
    public Long getDepotId() { return depotId; }
    public LocalDate getPlanDate() { return planDate; }
    public PlanStatus getStatus() { return status; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
