package com.routewise.cost;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Prices the optimizer weighs, effective from a date (architecture §9).
 *
 * <p>Effective-dated rather than a single row: a plan is priced with the
 * parameters of its own day, so re-running an old plan after the fuel price
 * changed reproduces the old result instead of silently shifting it.
 */
@Entity
@Table(name = "cost_parameters")
public class CostParameters {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fuel_price_per_l", nullable = false)
    private BigDecimal fuelPricePerL;

    @Column(name = "driver_cost_per_hour", nullable = false)
    private BigDecimal driverCostPerHour;

    /** TL per (priority × arrival hour); see python/app/vrp/objective.py. */
    @Column(name = "priority_penalty", nullable = false)
    private BigDecimal priorityPenalty;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    protected CostParameters() {}

    public CostParameters(BigDecimal fuelPricePerL, BigDecimal driverCostPerHour,
                          BigDecimal priorityPenalty, LocalDate effectiveFrom) {
        this.fuelPricePerL = fuelPricePerL;
        this.driverCostPerHour = driverCostPerHour;
        this.priorityPenalty = priorityPenalty;
        this.effectiveFrom = effectiveFrom;
    }

    public Long getId() { return id; }
    public BigDecimal getFuelPricePerL() { return fuelPricePerL; }
    public BigDecimal getDriverCostPerHour() { return driverCostPerHour; }
    public BigDecimal getPriorityPenalty() { return priorityPenalty; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
}
