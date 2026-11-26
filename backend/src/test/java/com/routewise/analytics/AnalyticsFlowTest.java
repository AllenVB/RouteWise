package com.routewise.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.ExportRequest;
import com.routewise.optimization.client.OptimizerDtos.SavingsRequest;
import com.routewise.optimization.client.OptimizerDtos.SavingsResult;
import com.routewise.support.ApiTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * F9 uçtan uca: gerçek veritabanından okunan satırlar optimizasyon servisine ne
 * gidiyor — servis (pandas, openpyxl, matplotlib) taklit edilir.
 */
class AnalyticsFlowTest extends ApiTest {

    @MockitoBean
    private OptimizerClient optimizer;

    private long depot;
    private long vehicle;
    private long recipient;
    private long address;

    @BeforeEach
    void seed() {
        user("firma@test", "COMPANY");
        recipient = recipient(user("alici@test", "RECIPIENT"));
        depot = jdbc.queryForObject("""
                INSERT INTO depots (name, location, opening_time, closing_time, max_route_duration_min, snapped_node_id)
                VALUES ('Test Depo', ST_SetSRID(ST_MakePoint(27.9767, 40.3521), 4326), '08:00', '18:00', 480, 1)
                RETURNING id
                """, Long.class);
        vehicle = jdbc.queryForObject("""
                INSERT INTO vehicles (depot_id, plate, capacity_kg, fuel_l_per_100km, fixed_cost_per_day)
                VALUES (?, '10 A 1', 500, 9, 250) RETURNING id
                """, Long.class, depot);
        address = jdbc.queryForObject("""
                INSERT INTO addresses (recipient_id, address_text, location, verification_status, snapped_node_id,
                                       verified_by, verified_at)
                VALUES (?, 'Sokak', ST_SetSRID(ST_MakePoint(27.97, 40.35), 4326), 'DOGRULANDI', 5,
                        (SELECT id FROM users WHERE email = 'firma@test'), now()) RETURNING id
                """, Long.class, recipient);

        // Sevk edilmiş: ilk optimizasyonun baseline'ı (1200) eski; son baseline (1000) + uygulanan (800).
        long p1 = plan(LocalDate.of(2026, 11, 2), "SEVK_EDILDI");
        run(p1, "NAIVE", false, true, 1200, 24000, 3);
        run(p1, "ROUTEWISE", false, false, 850, 17000, 2);
        run(p1, "NAIVE", false, true, 1000, 20000, 3);
        long applied = run(p1, "ROUTEWISE", true, false, 800, 16000, 2);
        stops(applied, 2);
        // Tamamlanmış, NAIVE uygulanmış: kendi baseline'ı, tasarruf yok.
        long p2 = plan(LocalDate.of(2026, 11, 20), "TAMAMLANDI");
        run(p2, "NAIVE", true, true, 500, 9000, 1);
        // Taslak: henüz sürülmedi, sayılmaz.
        long p3 = plan(LocalDate.of(2026, 11, 10), "TASLAK");
        run(p3, "NAIVE", false, true, 900, 1, 1);
        run(p3, "ROUTEWISE", true, false, 100, 1, 1);
        // Aralık dışı.
        long p4 = plan(LocalDate.of(2025, 1, 6), "TAMAMLANDI");
        run(p4, "NAIVE", false, true, 300, 1, 1);
        run(p4, "ROUTEWISE", true, false, 200, 1, 1);

        doAnswer(inv -> new SavingsResult(((SavingsRequest) inv.getArgument(0)).period(), List.of(), null))
                .when(optimizer).savings(any());
        doReturn(new byte[] {'P', 'K', 3, 4}).when(optimizer).exportReport(any());
    }

    private long plan(LocalDate date, String status) {
        return jdbc.queryForObject("INSERT INTO delivery_plans (depot_id, plan_date, status) VALUES (?, ?, ?) RETURNING id",
                Long.class, depot, date, status);
    }

    private long run(long plan, String alg, boolean applied, boolean baseline, double cost, double distance, int vehicles) {
        return jdbc.queryForObject("""
                INSERT INTO optimization_runs (plan_id, algorithm, is_applied, is_baseline, total_cost_try,
                                               total_distance_m, vehicle_count)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, plan, alg, applied, baseline, cost, distance, vehicles);
    }

    private void stops(long run, int n) {
        long route = jdbc.queryForObject("INSERT INTO routes (run_id, vehicle_id) VALUES (?, ?) RETURNING id",
                Long.class, run, vehicle);
        for (int i = 1; i <= n; i++) {
            long shipment = jdbc.queryForObject("""
                    INSERT INTO shipments (order_number, recipient_id, address_id, weight_kg, status)
                    VALUES (?, ?, ?, 2, 'PLANLANDI') RETURNING id
                    """, Long.class, "BND-2026-0700" + i, recipient, address);
            jdbc.update("INSERT INTO route_stops (route_id, shipment_id, sequence_no, node_id) VALUES (?, ?, ?, 5)",
                    route, shipment, i);
        }
    }

    @Test
    @DisplayName("tasarruf: yalnızca sevk edilmiş planlar, her biri kendi son baseline'ıyla")
    void shouldSendDispatchedPlansWithTheirBaseline() throws Exception {
        mvc.perform(get("/api/v1/analytics/savings").param("period", "WEEK")
                        .param("from", "2026-11-01").param("to", "2026-11-30")
                        .header("Authorization", bearer("firma@test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("WEEK"))
                .andExpect(jsonPath("$.from").value("2026-11-01"))
                .andExpect(jsonPath("$.result.period").value("WEEK"));

        ArgumentCaptor<SavingsRequest> sent = ArgumentCaptor.forClass(SavingsRequest.class);
        verify(optimizer).savings(sent.capture());
        var rows = sent.getValue().rows();
        assertThat(rows).extracting(r -> r.planDate())
                .containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 20));
        assertThat(rows.get(0).optimizedCostTry()).isEqualTo(800.0);
        assertThat(rows.get(0).baselineCostTry()).isEqualTo(1000.0);
        assertThat(rows.get(0).baselineDistanceM()).isEqualTo(20000.0);
        assertThat(rows.get(0).shipmentCount()).isEqualTo(2);
        assertThat(rows.get(0).vehicleCount()).isEqualTo(2);
        assertThat(rows.get(1).optimizedCostTry()).isEqualTo(500.0);
        assertThat(rows.get(1).baselineCostTry()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("rapor: dosya ekli indirilir; Seviye 1 aralıktan, Seviye 2 son tamamlanan deneyden")
    void shouldExportWithLevel1StatsAndLatestExperiment() throws Exception {
        jdbc.update("""
                INSERT INTO path_comparison_runs (algorithm, from_node_id, to_node_id, path_length_m, nodes_visited,
                                                  deviation_pct, computation_time_ms, found, created_at)
                VALUES ('DIJKSTRA', 1, 2, 1000, 3000, 0, 10, true, '2026-11-05T10:00:00Z'),
                       ('DIJKSTRA', 1, 3, 2000, 5000, 0, 20, true, '2026-11-06T10:00:00Z'),
                       ('DFS', 1, 2, 9000, 4000, 800, 8, true, '2026-11-05T10:00:00Z'),
                       ('DFS', 1, 3, 9000, 4000, 400, 8, true, '2026-11-06T10:00:00Z'),
                       ('DFS', 1, 4, 9000, 4000, 9999, 8, true, '2025-01-01T10:00:00Z')
                """);
        long exp = jdbc.queryForObject("""
                INSERT INTO experiments (status, scenario_count, stop_count, radius_m, repeats, base_seed, completed,
                                         finished_at, summary)
                VALUES ('DONE', 2, 10, 3000, 3, 2026, 2, now(), CAST(? AS jsonb)) RETURNING id
                """, Long.class, """
                {"usableScenarios":2,"alpha":0.05,
                 "algorithms":[{"algorithm":"ROUTEWISE","scenarios":2,"medianGapPct":0,"meanGapPct":0,"maxGapPct":0,
                                "optimalCount":2,"medianTimeMs":240}],
                 "tests":[{"candidate":"ROUTEWISE","reference":"NAIVE","n":2,"candidateBetter":2,"ties":0,
                           "candidateWorse":0,"meanDiff":-50,"medianDiff":-50,"meanDiffPct":-14.5,
                           "wilcoxonStatistic":0,"wilcoxonP":0.25,"tStatistic":-9,"tP":0.03,"significant":false,
                           "note":null}],
                 "claim":"RouteWise, 2 senaryoda kanıtlı optimumun ortalama %0,00 içinde."}
                """);
        for (int seed : new int[] {2026, 2027}) {
            long c = jdbc.queryForObject("""
                    INSERT INTO comparisons (level, params, result, experiment_id, scenario_seed)
                    VALUES ('TOUR', '{}', '{}', ?, ?) RETURNING id
                    """, Long.class, exp, seed);
            jdbc.update("""
                    INSERT INTO tour_comparison_runs (comparison_id, algorithm, stop_count, feasible, gap_pct,
                                                      computation_time_ms, reference_is_optimal)
                    VALUES (?, 'NAIVE', 10, true, ?, 0.01, true), (?, 'ROUTEWISE', 10, true, 0, 240, true)
                    """, c, 10.0 + seed % 2, c);
        }

        mvc.perform(get("/api/v1/analytics/export").param("format", "XLSX").param("period", "MONTH")
                        .param("from", "2026-11-01").param("to", "2026-11-30")
                        .header("Authorization", bearer("firma@test")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"routewise-rapor-2026-11-01-2026-11-30.xlsx\""))
                .andExpect(content().bytes(new byte[] {'P', 'K', 3, 4}));

        ArgumentCaptor<ExportRequest> sent = ArgumentCaptor.forClass(ExportRequest.class);
        verify(optimizer).exportReport(sent.capture());
        ExportRequest r = sent.getValue();
        assertThat(r.format()).isEqualTo("XLSX");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.pathStats()).extracting(p -> p.algorithm()).containsExactly("DIJKSTRA", "DFS");
        assertThat(r.pathStats().get(1).runs()).isEqualTo(2);
        assertThat(r.pathStats().get(1).medianDeviationPct()).isCloseTo(600.0, within(1e-9));
        assertThat(r.pathStats().get(1).maxDeviationPct()).isEqualTo(800.0);
        assertThat(r.pathStats().get(0).medianNodesVisited()).isEqualTo(4000.0);
        assertThat(r.experiment().id()).isEqualTo(exp);
        assertThat(r.experiment().baseSeed()).isEqualTo(2026);
        assertThat(r.experiment().tests()).singleElement().satisfies(t -> {
            assertThat(t.reference()).isEqualTo("NAIVE");
            assertThat(t.tP()).isEqualTo(0.03);
        });
        assertThat(r.experiment().gaps()).containsOnlyKeys("NAIVE", "ROUTEWISE");
        assertThat(r.experiment().gaps().get("NAIVE")).containsExactly(10.0, 11.0);
        assertThat(r.experiment().claim()).startsWith("RouteWise, 2 senaryoda");
    }

    @Test
    @DisplayName("deney yoksa rapor Seviye 2'siz gider; PDF de ekli iner")
    void shouldExportPdfWithoutExperiment() throws Exception {
        mvc.perform(get("/api/v1/analytics/export").param("format", "PDF")
                        .param("from", "2026-11-01").param("to", "2026-11-30")
                        .header("Authorization", bearer("firma@test")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.endsWith(".pdf\"")));
        ArgumentCaptor<ExportRequest> sent = ArgumentCaptor.forClass(ExportRequest.class);
        verify(optimizer).exportReport(sent.capture());
        assertThat(sent.getValue().experiment()).isNull();
        assertThat(sent.getValue().pathStats()).isEmpty();
    }

    @Test
    @DisplayName("hatalı parametre 400 — 500 değil")
    void shouldRejectBadParameters() throws Exception {
        String token = bearer("firma@test");
        mvc.perform(get("/api/v1/analytics/savings").param("period", "FOO").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("'period' parametresi geçersiz: FOO"));
        mvc.perform(get("/api/v1/analytics/savings").param("from", "2026-12-01").param("to", "2026-11-01")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analytics/savings").param("from", "2010-01-01").param("to", "2026-11-01")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analytics/export").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'format' parametresi gerekli."));
        mvc.perform(get("/api/v1/analytics/export").param("format", "CSV").header("Authorization", token))
                .andExpect(status().isBadRequest());
        verify(optimizer, never()).savings(any());
        verify(optimizer, never()).exportReport(any());
    }
}
