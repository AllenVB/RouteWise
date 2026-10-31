package com.routewise.optimization;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One vehicle's route within a run. Totals include the return leg to the depot. */
@Entity
@Table(name = "routes")
public class PlannedRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "courier_id")
    private Long courierId;

    @Column(name = "total_distance_m")
    private BigDecimal totalDistanceM;

    @Column(name = "total_duration_s")
    private Integer totalDurationS;

    @Column(name = "total_load_kg")
    private BigDecimal totalLoadKg;

    @Column(name = "total_fuel_l")
    private BigDecimal totalFuelL;

    @Column(name = "total_cost_try")
    private BigDecimal totalCostTry;

    @Column(nullable = false)
    private String status = "PLANLANDI";

    protected PlannedRoute() {}

    public PlannedRoute(Long runId, Long vehicleId, BigDecimal totalDistanceM,
                        Integer totalDurationS, BigDecimal totalLoadKg) {
        this(runId, vehicleId, totalDistanceM, totalDurationS, totalLoadKg, null, null);
    }

    public PlannedRoute(Long runId, Long vehicleId, BigDecimal totalDistanceM,
                        Integer totalDurationS, BigDecimal totalLoadKg,
                        BigDecimal totalFuelL, BigDecimal totalCostTry) {
        this.runId = runId;
        this.vehicleId = vehicleId;
        this.totalDistanceM = totalDistanceM;
        this.totalDurationS = totalDurationS;
        this.totalLoadKg = totalLoadKg;
        this.totalFuelL = totalFuelL;
        this.totalCostTry = totalCostTry;
    }

    public void assignCourier(Long courierId) {
        this.courierId = courierId;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Long getVehicleId() { return vehicleId; }
    public Long getCourierId() { return courierId; }
    public BigDecimal getTotalDistanceM() { return totalDistanceM; }
    public Integer getTotalDurationS() { return totalDurationS; }
    public BigDecimal getTotalLoadKg() { return totalLoadKg; }
    public BigDecimal getTotalFuelL() { return totalFuelL; }
    public BigDecimal getTotalCostTry() { return totalCostTry; }
    public String getStatus() { return status; }
}
