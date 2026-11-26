package com.routewise.vehicle;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fleet vehicle. Seeded, read-only — but a table, not code: "different vehicle
 * scenarios" (success criterion 8) means the fleet must change without a build.
 */
@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "depot_id", nullable = false)
    private Long depotId;

    @Column(nullable = false)
    private String plate;

    @Column(name = "capacity_kg", nullable = false)
    private BigDecimal capacityKg;

    @Column(name = "capacity_volume_m3")
    private BigDecimal capacityVolumeM3;

    @Column(name = "fuel_l_per_100km", nullable = false)
    private BigDecimal fuelLPer100km;

    @Column(name = "fuel_type", nullable = false)
    private String fuelType;

    @Column(name = "fixed_cost_per_day", nullable = false)
    private BigDecimal fixedCostPerDay;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Vehicle() {}

    /** Visible for tests; production vehicles come from the seed. */
    public Vehicle(Long id, Long depotId, String plate, BigDecimal capacityKg) {
        this.id = id;
        this.depotId = depotId;
        this.plate = plate;
        this.capacityKg = capacityKg;
        this.fuelLPer100km = new BigDecimal("9.50");
        this.fuelType = "DIESEL";
        this.fixedCostPerDay = BigDecimal.ZERO;
    }

    public Long getId() { return id; }
    public Long getDepotId() { return depotId; }
    public String getPlate() { return plate; }
    public BigDecimal getCapacityKg() { return capacityKg; }
    public BigDecimal getCapacityVolumeM3() { return capacityVolumeM3; }
    public BigDecimal getFuelLPer100km() { return fuelLPer100km; }
    public String getFuelType() { return fuelType; }
    public BigDecimal getFixedCostPerDay() { return fixedCostPerDay; }
    public boolean isActive() { return active; }
}
