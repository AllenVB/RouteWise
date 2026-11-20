package com.routewise.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.RouteOut;
import com.routewise.optimization.client.OptimizerDtos.SolutionOut;
import com.routewise.optimization.client.OptimizerDtos.TourAlgorithmOut;
import com.routewise.optimization.client.OptimizerDtos.TourCompareRequest;
import com.routewise.optimization.client.OptimizerDtos.TourCompareResult;
import com.routewise.optimization.client.OptimizerDtos.UnassignedOut;
import com.routewise.routing.matrix.DistanceMatrix;
import com.routewise.routing.matrix.DistanceMatrixProvider;
import com.routewise.support.ApiTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Seviye 2 karşılaştırması uçtan uca: gerçek filtre zinciri, gerçek veritabanı
 * (plan, kargolar, adres doğrulaması); optimizasyon servisi ve matris taklit edilir.
 */
class TourComparisonFlowTest extends ApiTest {

    private static final ZoneId TZ = ZoneId.of("Europe/Istanbul");

    @MockitoBean
    private OptimizerClient optimizer;

    @MockitoBean
    private DistanceMatrixProvider matrices;

    private long plan;
    private long vehicle;
    private long second;
    private long s1, s2, s3, pending;

    @BeforeEach
    void seed() {
        user("firma@test", "COMPANY");
        long recipient = recipient(user("alici@test", "RECIPIENT"));
        long depot = jdbc.queryForObject("""
                INSERT INTO depots (name, location, opening_time, closing_time, max_route_duration_min, snapped_node_id)
                VALUES ('Test Depo', ST_SetSRID(ST_MakePoint(27.9767, 40.3521), 4326), '08:00', '18:00', 480, 1)
                RETURNING id
                """, Long.class);
        vehicle = vehicle(depot, "10 T 1");
        second = vehicle(depot, "10 T 2");
        jdbc.update("""
                INSERT INTO cost_parameters (fuel_price_per_l, driver_cost_per_hour, priority_penalty, effective_from)
                VALUES (44, 150, 2.5, DATE '2026-01-01')
                """);
        long verified = address(recipient, "DOGRULANDI", 101L);
        long waiting = address(recipient, "DOGRULAMA_BEKLIYOR", null);
        s1 = shipment("BND-2026-09001", recipient, verified, 1);
        s2 = shipment("BND-2026-09002", recipient, verified, 0);
        s3 = shipment("BND-2026-09003", recipient, verified, 3);
        pending = shipment("BND-2026-09004", recipient, waiting, 0);
        plan = jdbc.queryForObject("INSERT INTO delivery_plans (depot_id, plan_date) VALUES (?, ?) RETURNING id",
                Long.class, depot, LocalDate.now(TZ));

        when(matrices.forNodes(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return new DistanceMatrix("h", ids, new Double[ids.size()][ids.size()],
                    new Double[ids.size()][ids.size()], 0);
        });
        when(optimizer.compareTour(any())).thenAnswer(inv -> answer(inv.getArgument(0)));
    }

    private long vehicle(long depot, String plate) {
        return jdbc.queryForObject("""
                INSERT INTO vehicles (depot_id, plate, capacity_kg, fuel_l_per_100km, fixed_cost_per_day)
                VALUES (?, ?, 500, 9, 250) RETURNING id
                """, Long.class, depot, plate);
    }

    private long address(long recipient, String status, Long node) {
        return jdbc.queryForObject("""
                INSERT INTO addresses (recipient_id, address_text, location, verification_status, snapped_node_id,
                                       verified_by, verified_at)
                VALUES (?, 'Test Sokağı', ST_SetSRID(ST_MakePoint(27.97, 40.35), 4326), ?, ?,
                        CASE WHEN ? = 'DOGRULANDI' THEN (SELECT id FROM users WHERE email = 'firma@test') END,
                        CASE WHEN ? = 'DOGRULANDI' THEN now() END)
                RETURNING id
                """, Long.class, recipient, status, node, status, status);
    }

    private long shipment(String order, long recipient, long address, int priority) {
        return jdbc.queryForObject("""
                INSERT INTO shipments (order_number, recipient_id, address_id, weight_kg, priority, status)
                VALUES (?, ?, ?, 2.5, ?, 'BEKLEMEDE') RETURNING id
                """, Long.class, order, recipient, address, priority);
    }

    private static SolutionOut solution(String alg, double objective) {
        RouteOut r = new RouteOut(1, List.of(), 100, 10, 4200, 600, 300, 900, 7.5, 0.38, objective - 2);
        return new SolutionOut(alg, List.of(r), 4200, 900, 1, 3.0, 0.38, objective - 2, 2.0, objective);
    }

    private static TourCompareResult answer(TourCompareRequest r) {
        boolean exact = r.stops().size() <= 10;
        var out = new java.util.ArrayList<TourAlgorithmOut>(List.of(
                new TourAlgorithmOut("NAIVE", true, null, solution("NAIVE", 130), 0.2, 30.0, null, 130.0, 130.0, null),
                new TourAlgorithmOut("GREEDY_NN", true, null, solution("GREEDY_NN", 110), 0.4, 10.0, null, 110.0, 110.0, null),
                new TourAlgorithmOut("SAVINGS", true, null, solution("SAVINGS", 104), 0.9, 4.0, null, 104.0, 104.0, null),
                new TourAlgorithmOut("ROUTEWISE", true, null, solution("ROUTEWISE", 100.5), 410.0, 0.5, 8L, 100.0, 101.0, null)));
        if (exact) {
            out.add(new TourAlgorithmOut("DFS_EXHAUSTIVE", true, null, solution("DFS_EXHAUSTIVE", 100), 35.0, 0.0,
                    null, null, null, 4321L));
        }
        return new TourCompareResult(r.repeats(), List.of(7L, 8L, 9L).subList(0, r.repeats()),
                exact ? "DFS_EXHAUSTIVE" : "ROUTEWISE", exact, exact ? null : "11 durak",
                out, List.of());
    }

    private ResultActions compare(String body) throws Exception {
        var req = post("/api/v1/plans/" + plan + "/compare/tour").header("Authorization", bearer("firma@test"));
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    @Test
    @DisplayName("varsayılanlar: ilk aktif araç, doğrulanmış tüm kargolar oluşturulma sırasıyla, 3 tekrar")
    void shouldCompareAllRoutableShipmentsWithFirstVehicle() throws Exception {
        String body = compare(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("TOUR"))
                .andExpect(jsonPath("$.planId").value(plan))
                .andExpect(jsonPath("$.vehicleId").value(vehicle))
                .andExpect(jsonPath("$.stopCount").value(3))
                .andExpect(jsonPath("$.repeats").value(3))
                .andExpect(jsonPath("$.referenceAlgorithm").value("DFS_EXHAUSTIVE"))
                .andExpect(jsonPath("$.referenceIsOptimal").value(true))
                .andExpect(jsonPath("$.results.length()").value(5))
                .andExpect(jsonPath("$.results[3].randomSeed").value(8))
                .andExpect(jsonPath("$.excluded[0].shipmentId").value(pending))
                .andExpect(jsonPath("$.excluded[0].reason").value("ADDRESS_NOT_VERIFIED"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*?\"id\":(\\d+).*", "$1"));

        ArgumentCaptor<TourCompareRequest> sent = ArgumentCaptor.forClass(TourCompareRequest.class);
        verify(optimizer).compareTour(sent.capture());
        TourCompareRequest r = sent.getValue();
        assertThat(r.stops()).extracting(st -> st.id()).containsExactly(s1, s2, s3);
        assertThat(r.stops()).extracting(st -> st.priority()).containsExactly(1, 0, 3);
        assertThat(r.vehicles()).singleElement().satisfies(v -> assertThat(v.id()).isEqualTo(vehicle));
        assertThat(r.maxRouteDurationS()).isEqualTo(480 * 60.0);
        assertThat(r.costParams().fuelPricePerL()).isEqualTo(44.0);
        assertThat(r.repeats()).isEqualTo(3);
        assertThat(r.randomSeed()).isNull();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT algorithm, stop_count, feasible, objective, gap_pct, reference_is_optimal, random_seed, explored
                FROM tour_comparison_runs WHERE comparison_id = ? ORDER BY id
                """, id);
        assertThat(rows).extracting(x -> x.get("algorithm"))
                .containsExactly("NAIVE", "GREEDY_NN", "SAVINGS", "ROUTEWISE", "DFS_EXHAUSTIVE");
        assertThat(rows.get(3)).containsEntry("objective", new BigDecimal("100.50"))
                .containsEntry("gap_pct", new BigDecimal("0.500"))
                .containsEntry("random_seed", 8L)
                .containsEntry("stop_count", 3)
                .containsEntry("reference_is_optimal", true);
        assertThat(rows.get(4)).containsEntry("explored", 4321L);
        // Plan yalnızca ölçülür: koşu yazılmaz, kargolar havuzda kalır.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_runs", Integer.class)).isZero();

        mvc.perform(get("/api/v1/comparisons/" + id).header("Authorization", bearer("firma@test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("TOUR"))
                .andExpect(jsonPath("$.stopCount").value(3))
                .andExpect(jsonPath("$.results[4].algorithm").value("DFS_EXHAUSTIVE"))
                .andExpect(jsonPath("$.excluded[0].shipmentId").value(pending));
    }

    @Test
    @DisplayName("alt küme, araç, tekrar ve tohum seçilebilir")
    void shouldUseChosenSubsetVehicleRepeatsAndSeed() throws Exception {
        compare("{\"vehicleId\":" + second + ",\"shipmentIds\":[" + s3 + "," + s1 + "],\"repeats\":2,\"randomSeed\":42}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleId").value(second))
                .andExpect(jsonPath("$.stopCount").value(2))
                .andExpect(jsonPath("$.excluded").isEmpty());

        ArgumentCaptor<TourCompareRequest> sent = ArgumentCaptor.forClass(TourCompareRequest.class);
        verify(optimizer).compareTour(sent.capture());
        // Naive sırası oluşturulma sırasıdır, istekteki sıra değil.
        assertThat(sent.getValue().stops()).extracting(st -> st.id()).containsExactly(s1, s3);
        assertThat(sent.getValue().randomSeed()).isEqualTo(42L);
        assertThat(sent.getValue().repeats()).isEqualTo(2);
    }

    @Test
    @DisplayName("hatalı girdi: rotaya alınamayan kargo, tekrar eden kargo, tekrar sınırı, bilinmeyen araç, plan")
    void shouldRejectInvalidInput() throws Exception {
        compare("{\"shipmentIds\":[" + s1 + "," + pending + "]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(String.valueOf(pending))));
        compare("{\"shipmentIds\":[" + s1 + "," + s1 + "]}").andExpect(status().isBadRequest());
        compare("{\"repeats\":11}").andExpect(status().isBadRequest());
        compare("{\"repeats\":0}").andExpect(status().isBadRequest());
        compare("{\"vehicleId\":987654}").andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/plans/987654/compare/tour").header("Authorization", bearer("firma@test")))
                .andExpect(status().isNotFound());

        verify(optimizer, never()).compareTour(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparisons", Integer.class)).isZero();
    }

    @Test
    @DisplayName("tek araca sığmıyorsa servisin 409'u geçer, kayıt kalmaz")
    void shouldPassInfeasibilityThrough() throws Exception {
        doThrow(new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, "Kargolar tek araca sığmıyor."))
                .when(optimizer).compareTour(any());

        compare(null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_FEASIBLE_SOLUTION"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tour_comparison_runs", Integer.class)).isZero();
    }

    @Test
    @DisplayName("ulaşılamayan durak karşılaştırılan durak sayısından düşer")
    void shouldCountUnassignedOut() throws Exception {
        doAnswer(inv -> {
            TourCompareResult r = answer(inv.getArgument(0));
            return new TourCompareResult(r.repeats(), r.seeds(), r.referenceAlgorithm(), r.referenceIsOptimal(),
                    r.exhaustiveSkipped(), r.results(), List.of(new UnassignedOut(s2, "UNREACHABLE")));
        }).when(optimizer).compareTour(any());

        compare(null).andExpect(status().isOk())
                .andExpect(jsonPath("$.stopCount").value(2))
                .andExpect(jsonPath("$.unassigned[0].stopId").value(s2));
        assertThat(jdbc.queryForObject("SELECT DISTINCT stop_count FROM tour_comparison_runs", Integer.class))
                .isEqualTo(2);
    }
}
