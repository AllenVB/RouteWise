package com.routewise.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.routewise.cost.CostParameters;
import com.routewise.cost.CostParametersRepository;
import com.routewise.plan.DeliveryPlan;
import com.routewise.plan.DeliveryPlanRepository;
import com.routewise.shipment.Shipment;
import com.routewise.shipment.ShipmentRepository;
import com.routewise.support.PostgisRepositoryTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Custom queries of the planning chain, against the real schema and its constraints. */
class PlanningRepositoriesTest extends PostgisRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);

    @Autowired private ShipmentRepository shipments;
    @Autowired private DeliveryPlanRepository plans;
    @Autowired private OptimizationRunRepository runs;
    @Autowired private CostParametersRepository costs;
    @Autowired private JdbcTemplate jdbc;

    private long addressId;
    private long depotId;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users (email, password_hash, full_name, role) "
                + "VALUES ('a@ornek.com', 'x', 'Alıcı', 'RECIPIENT')");
        jdbc.update("INSERT INTO recipients (user_id) SELECT id FROM users WHERE email = 'a@ornek.com'");
        Long recipientId = jdbc.queryForObject("SELECT id FROM recipients", Long.class);
        addressId = jdbc.queryForObject("""
                INSERT INTO addresses (recipient_id, address_text, location, verification_status,
                                       verified_by, verified_at)
                VALUES (?, 'Yeni Mah.', ST_SetSRID(ST_MakePoint(27.97, 40.35), 4326), 'DOGRULANDI',
                        (SELECT id FROM users LIMIT 1), now())
                RETURNING id
                """, Long.class, recipientId);
        depotId = jdbc.queryForObject("""
                INSERT INTO depots (name, location, max_route_duration_min)
                VALUES ('Test Depo', ST_SetSRID(ST_MakePoint(27.9767, 40.3521), 4326), 540)
                RETURNING id
                """, Long.class);
    }

    private Shipment shipment(String orderNumber) {
        return shipments.saveAndFlush(new Shipment(orderNumber,
                jdbc.queryForObject("SELECT id FROM recipients", Long.class),
                addressId, new BigDecimal("1.00"), (short) 0, 5));
    }

    private void setStatus(Shipment s, String status, LocalDate postponedTo) {
        jdbc.update("UPDATE shipments SET status = ?, postponed_to = ? WHERE id = ?",
                status, postponedTo, s.getId());
    }

    @Test
    @DisplayName("Planlanabilir: bekleyenler + o güne kadar ertelenenler, oluşturulma sırasıyla")
    void plannableShipments() {
        Shipment first = shipment("BND-2026-00001");
        Shipment yolda = shipment("BND-2026-00002");
        Shipment ertelenmisBugune = shipment("BND-2026-00003");
        Shipment ertelenmisYarina = shipment("BND-2026-00004");
        Shipment last = shipment("BND-2026-00005");
        setStatus(yolda, "YOLDA", null);
        setStatus(ertelenmisBugune, "ERTELENDI", DAY);
        setStatus(ertelenmisYarina, "ERTELENDI", DAY.plusDays(1));

        assertThat(shipments.findPlannableOn(DAY))
                .extracting(Shipment::getOrderNumber)
                .containsExactly("BND-2026-00001", "BND-2026-00003", "BND-2026-00005");
        assertThat(first.getId()).isLessThan(last.getId());
    }

    @Test
    @DisplayName("Uygulanan koşu geri çekilince aynı plana yeni uygulanan koşu yazılabilir")
    void withdrawAllowsNewAppliedRun() {
        DeliveryPlan plan = plans.saveAndFlush(new DeliveryPlan(depotId, DAY, null));
        runs.saveAndFlush(run(plan, true));

        assertThat(runs.withdrawApplied(plan.getId())).isEqualTo(1);
        runs.saveAndFlush(run(plan, true));

        assertThat(runs.findByPlanIdOrderByIdDesc(plan.getId()))
                .extracting(OptimizationRun::isApplied).containsExactly(true, false);
    }

    @Test
    @DisplayName("Geri çekmeden ikinci uygulanan koşu şema tarafından reddedilir")
    void schemaRejectsTwoAppliedRuns() {
        DeliveryPlan plan = plans.saveAndFlush(new DeliveryPlan(depotId, DAY, null));
        runs.saveAndFlush(run(plan, true));

        assertThatThrownBy(() -> runs.saveAndFlush(run(plan, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Plan kilidi gerçek PostgreSQL'de çalışır ve planı döndürür")
    void lockById() {
        DeliveryPlan plan = plans.saveAndFlush(new DeliveryPlan(depotId, DAY, null));

        assertThat(plans.lockById(plan.getId())).isPresent();
        assertThat(plans.findByDepotIdAndPlanDate(depotId, DAY)).isPresent();
    }

    @Test
    @DisplayName("Maliyet parametresi: plan gününde geçerli olan, yani o güne kadar başlayan en son satır")
    void costParametersInForceOnDate() {
        costs.saveAndFlush(new CostParameters(new BigDecimal("40.00"), new BigDecimal("140.00"),
                new BigDecimal("2.50"), LocalDate.of(2026, 1, 1)));
        costs.saveAndFlush(new CostParameters(new BigDecimal("44.00"), new BigDecimal("150.00"),
                new BigDecimal("2.50"), DAY));
        costs.saveAndFlush(new CostParameters(new BigDecimal("50.00"), new BigDecimal("160.00"),
                new BigDecimal("2.50"), DAY.plusDays(1)));

        assertThat(costs.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(DAY))
                .get().extracting(CostParameters::getFuelPricePerL).isEqualTo(new BigDecimal("44.00"));
        assertThat(costs.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(DAY.minusDays(1)))
                .get().extracting(CostParameters::getFuelPricePerL).isEqualTo(new BigDecimal("40.00"));
        assertThat(costs.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(LocalDate.of(2025, 12, 31)))
                .isEmpty();
    }

    private static OptimizationRun run(DeliveryPlan plan, boolean applied) {
        return new OptimizationRun(plan.getId(), Algorithm.GREEDY_NN, applied, false,
                new BigDecimal("1000.00"), 600, (short) 1, 3);
    }
}
