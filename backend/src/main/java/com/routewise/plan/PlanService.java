package com.routewise.plan;

import java.time.LocalDate;

import com.routewise.common.error.ApiException;
import com.routewise.depot.DepotService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final DeliveryPlanRepository plans;
    private final DepotService depots;

    public PlanService(DeliveryPlanRepository plans, DepotService depots) {
        this.plans = plans;
        this.depots = depots;
    }

    @Transactional(readOnly = true)
    public DeliveryPlan get(Long id) {
        return plans.findById(id).orElseThrow(() -> ApiException.notFound("Plan"));
    }

    @Transactional
    public DeliveryPlan forDate(LocalDate date) {
        Long depotId = depots.primary().getId();
        return plans.findByDepotIdAndPlanDate(depotId, date)
                .orElseThrow(() -> ApiException.notFound("Bu günün planı"));
    }

    /**
     * Opens the day's plan, or returns the one already open.
     *
     * <p>Idempotent on purpose: "open today's plan" pressed twice is one plan, and
     * an error there would only push the screen into an extra lookup.
     *
     * @return the plan and whether this call created it
     */
    @Transactional
    public Opened open(LocalDate date, Long userId) {
        Long depotId = depots.primary().getId();
        var existing = plans.findByDepotIdAndPlanDate(depotId, date);
        if (existing.isPresent()) {
            return new Opened(existing.get(), false);
        }
        // Aynı anda iki açma isteğinde ikincisi uq_plans_depot_date'e çarpar;
        // tek firma kullanıcısı olan kapsamda bu kabul edilen bir risktir.
        DeliveryPlan saved = plans.save(new DeliveryPlan(depotId, date, userId));
        log.info("plan acildi: {} (depo {})", date, depotId);
        return new Opened(saved, true);
    }

    public record Opened(DeliveryPlan plan, boolean created) {}
}
