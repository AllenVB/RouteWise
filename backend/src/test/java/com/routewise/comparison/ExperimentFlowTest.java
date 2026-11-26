package com.routewise.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.PairedOut;
import com.routewise.optimization.client.OptimizerDtos.PairedRequest;
import com.routewise.optimization.client.OptimizerDtos.PairedResult;
import com.routewise.optimization.client.OptimizerDtos.RouteOut;
import com.routewise.optimization.client.OptimizerDtos.SolutionOut;
import com.routewise.optimization.client.OptimizerDtos.StopIn;
import com.routewise.optimization.client.OptimizerDtos.TourAlgorithmOut;
import com.routewise.optimization.client.OptimizerDtos.TourCompareResult;
import com.routewise.optimization.client.OptimizerDtos.TourScenarioRequest;
import com.routewise.optimization.client.OptimizerDtos.TourScenarioResult;
import com.routewise.support.ApiTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Deneyler uçtan uca: gerçek veritabanı, deney çağıranın iş parçacığında koşar
 * ({@code routewise.experiments.inline=true}); optimizasyon servisi taklit edilir.
 */
@TestPropertySource(properties = "routewise.experiments.inline=true")
class ExperimentFlowTest extends ApiTest {

    @MockitoBean
    private OptimizerClient optimizer;

    @Autowired
    private ExperimentService service;

    @Autowired
    private jakarta.persistence.EntityManager em;

    private long vehicle;

    @BeforeEach
    void seed() {
        user("firma@test", "COMPANY");
        long depot = jdbc.queryForObject("""
                INSERT INTO depots (name, location, opening_time, closing_time, max_route_duration_min, snapped_node_id)
                VALUES ('Test Depo', ST_SetSRID(ST_MakePoint(27.9767, 40.3521), 4326), '08:00', '18:00', 480, 77)
                RETURNING id
                """, Long.class);
        vehicle = jdbc.queryForObject("""
                INSERT INTO vehicles (depot_id, plate, capacity_kg, fuel_l_per_100km, fixed_cost_per_day)
                VALUES (?, '10 D 1', 500, 9, 250) RETURNING id
                """, Long.class, depot);
        jdbc.update("""
                INSERT INTO cost_parameters (fuel_price_per_l, driver_cost_per_hour, priority_penalty, effective_from)
                VALUES (44, 150, 2.5, DATE '2026-01-01')
                """);
        doAnswer(inv -> scenario(inv.getArgument(0))).when(optimizer).tourScenario(any());
        doAnswer(inv -> paired(inv.getArgument(0))).when(optimizer).pairedTests(any());
    }

    private static SolutionOut solution(double objective) {
        RouteOut r = new RouteOut(1, List.of(), 100, 10, 4200, 600, 300, 900, 7.5, 0.38, objective - 2);
        return new SolutionOut("X", List.of(r), 4200, 900, 1, 3.0, 0.38, objective - 2, 2.0, objective);
    }

    private static TourAlgorithmOut alg(String name, double objective, double optimum) {
        return new TourAlgorithmOut(name, true, null, solution(objective), 1.0,
                (objective - optimum) / optimum * 100.0, null, objective, objective, null);
    }

    /** Senaryo tohumdan: geliş sırası %20–24 pahalı, RouteWise her 5. senaryoda %1 kötü, yoksa optimum. */
    private static TourScenarioResult scenario(TourScenarioRequest r) {
        double opt = 100.0 + r.seed() % 7;
        double rw = r.seed() % 5 == 0 ? opt * 1.01 : opt;
        List<TourAlgorithmOut> out = List.of(
                alg("NAIVE", opt * (1.20 + (r.seed() % 5) / 100.0), opt), alg("GREEDY_NN", opt * 1.04, opt),
                alg("SAVINGS", opt * 1.02, opt), alg("ROUTEWISE", rw, opt), alg("DFS_EXHAUSTIVE", opt, opt));
        List<StopIn> stops = new ArrayList<>();
        for (int i = 1; i <= r.stopCount(); i++) {
            stops.add(new StopIn(i, 1000 + i, 3.0, 180, 0));
        }
        return new TourScenarioResult(r.seed(), stops, new TourCompareResult(r.repeats(), List.of(r.seed()),
                "DFS_EXHAUSTIVE", true, null, out, List.of()));
    }

    private static PairedResult paired(PairedRequest req) {
        return new PairedResult(req.alpha(), req.pairs().stream().map(p -> new PairedOut(p.candidate(),
                p.reference(), p.candidateValues().size(), p.candidateValues().size(), 0, 0, -5, -5, -4.2,
                0.0, 1e-6, -9.0, 1e-7, true, null)).toList());
    }

    private ResultActions start(String body) throws Exception {
        return mvc.perform(post("/api/v1/compare/experiments").header("Authorization", bearer("firma@test"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("senaryolar tohumdan sırayla koşar, kaydedilir; sonunda eşleştirilmiş testler ve iddia cümlesi")
    void shouldRunScenariosAndSummarize() throws Exception {
        String body = start("{\"scenarios\":5,\"stopCount\":8,\"radiusM\":2000,\"repeats\":2,\"seed\":1000}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completed").value(5))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.vehicleId").value(vehicle))
                .andExpect(jsonPath("$.scenarios.length()").value(5))
                .andExpect(jsonPath("$.scenarios[0].seed").value(1000))
                .andExpect(jsonPath("$.scenarios[4].seed").value(1004))
                .andExpect(jsonPath("$.summary.usableScenarios").value(5))
                .andExpect(jsonPath("$.summary.algorithms[3].algorithm").value("ROUTEWISE"))
                .andExpect(jsonPath("$.summary.algorithms[3].optimalCount").value(4))
                .andExpect(jsonPath("$.summary.algorithms[3].maxGapPct").value(org.hamcrest.Matchers.closeTo(1.0, 1e-9)))
                .andExpect(jsonPath("$.summary.algorithms[4].algorithm").value("DFS_EXHAUSTIVE"))
                .andExpect(jsonPath("$.summary.tests.length()").value(3))
                .andExpect(jsonPath("$.summary.claim").value(org.hamcrest.Matchers.startsWith(
                        "RouteWise, 5 senaryoda kanıtlı optimumun ortalama %0,20, en kötü %1,00 içinde; 4 senaryoda optimumu buldu.")))
                .andExpect(jsonPath("$.summary.claim").value(org.hamcrest.Matchers.containsString(
                        "Geliş sırası karşısında anlamlı ölçüde daha ucuz (Wilcoxon p = < 0,0001, n = 5).")))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll("^\\{\"id\":(\\d+).*", "$1"));

        ArgumentCaptor<TourScenarioRequest> sent = ArgumentCaptor.forClass(TourScenarioRequest.class);
        verify(optimizer, times(5)).tourScenario(sent.capture());
        assertThat(sent.getAllValues()).extracting(TourScenarioRequest::seed).containsExactly(1000L, 1001L, 1002L, 1003L, 1004L);
        TourScenarioRequest first = sent.getAllValues().get(0);
        assertThat(first.depotNodeId()).isEqualTo(77);
        assertThat(first.stopCount()).isEqualTo(8);
        assertThat(first.radiusM()).isEqualTo(2000.0);
        assertThat(first.repeats()).isEqualTo(2);
        assertThat(first.maxRouteDurationS()).isEqualTo(480 * 60.0);
        assertThat(first.vehicle().id()).isEqualTo(vehicle);

        ArgumentCaptor<PairedRequest> tests = ArgumentCaptor.forClass(PairedRequest.class);
        verify(optimizer).pairedTests(tests.capture());
        assertThat(tests.getValue().pairs()).extracting(p -> p.reference()).containsExactly("NAIVE", "GREEDY_NN", "SAVINGS");
        assertThat(tests.getValue().pairs().get(0).candidateValues()).hasSize(5);
        assertThat(tests.getValue().alpha()).isEqualTo(0.05);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparisons WHERE experiment_id = ? AND plan_id IS NULL",
                Integer.class, id)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM tour_comparison_runs r JOIN comparisons c ON c.id = r.comparison_id
                WHERE c.experiment_id = ? AND r.stop_count = 8
                """, Integer.class, id)).isEqualTo(25);

        // Bir senaryo, F8'deki tur karşılaştırmasıyla aynı biçimde açılır.
        long scenarioId = jdbc.queryForObject("SELECT id FROM comparisons WHERE experiment_id = ? AND scenario_seed = 1002",
                Long.class, id);
        mvc.perform(get("/api/v1/comparisons/" + scenarioId).header("Authorization", bearer("firma@test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("TOUR"))
                .andExpect(jsonPath("$.planId").doesNotExist())
                .andExpect(jsonPath("$.stopCount").value(8))
                .andExpect(jsonPath("$.results.length()").value(5));
        mvc.perform(get("/api/v1/compare/experiments").header("Authorization", bearer("firma@test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].scenarios").doesNotExist())
                .andExpect(jsonPath("$[0].summary.usableScenarios").value(5));
    }

    @Test
    @DisplayName("çözülemeyen senaryo sayılır ve atlanır, deney sürer")
    void shouldSkipInfeasibleScenario() throws Exception {
        doAnswer(inv -> {
            TourScenarioRequest r = inv.getArgument(0);
            if (r.seed() == 1001) {
                throw new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, "3000 m içinde 10 ulaşılabilir düğüm yok");
            }
            return scenario(r);
        }).when(optimizer).tourScenario(any());

        start("{\"scenarios\":5,\"seed\":1000}")
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completed").value(4))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.lastError").value(org.hamcrest.Matchers.startsWith("Senaryo 1001:")))
                .andExpect(jsonPath("$.summary.usableScenarios").value(4));
    }

    @Test
    @DisplayName("servis düşerse deney FAILED olur, o ana kadarki senaryolar kalır")
    void shouldAbortWhenOptimizerIsDown() throws Exception {
        doAnswer(inv -> {
            TourScenarioRequest r = inv.getArgument(0);
            if (r.seed() == 1002) {
                throw new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE, "Rota hesaplama servisine ulaşılamadı.");
            }
            return scenario(r);
        }).when(optimizer).tourScenario(any());

        start("{\"scenarios\":5,\"seed\":1000}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.completed").value(2))
                .andExpect(jsonPath("$.lastError").value("Rota hesaplama servisine ulaşılamadı."))
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.scenarios.length()").value(2));
        verify(optimizer, never()).pairedTests(any());
    }

    @Test
    @DisplayName("kesin optimumsuz senaryolar iddiaya girmez; iki senaryodan azsa test koşmaz")
    void shouldLeaveNonOptimalScenariosOutOfTheClaim() {
        var runner = new ExperimentRunner(null, null, optimizer, null, null, null, null, null, true);
        TourCompareResult optimal = scenario(new TourScenarioRequest(1, 3, 5, 1000, null, null, null, 1)).comparison();
        TourCompareResult bestFound = new TourCompareResult(1, List.of(4L), "ROUTEWISE", false, "11 durak",
                optimal.results().subList(0, 4), List.of());

        ExperimentRunner.Summary s = runner.summarize(List.of(optimal, bestFound));

        assertThat(s.usableScenarios()).isEqualTo(1);
        assertThat(s.tests()).isEmpty();
        assertThat(s.claim()).startsWith("RouteWise, 1 senaryoda");
        verify(optimizer, never()).pairedTests(any());
    }

    @Test
    @DisplayName("aynı anda tek deney: çalışan varsa 409 EXPERIMENT_RUNNING")
    void shouldRunOneAtATime() throws Exception {
        jdbc.update("""
                INSERT INTO experiments (status, scenario_count, stop_count, radius_m, repeats, base_seed)
                VALUES ('RUNNING', 30, 10, 3000, 3, 1)
                """);

        start("{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPERIMENT_RUNNING"));
        verify(optimizer, never()).tourScenario(any());
    }

    @Test
    @DisplayName("sunucu yeniden başlayınca yarım kalan deney FAILED işaretlenir")
    void shouldAbandonInterruptedExperiments() {
        long id = jdbc.queryForObject("""
                INSERT INTO experiments (status, scenario_count, stop_count, radius_m, repeats, base_seed, completed)
                VALUES ('RUNNING', 30, 10, 3000, 3, 1, 12) RETURNING id
                """, Long.class);

        service.abandonInterrupted();
        em.flush();   // test transaction'ı commit etmez; JDBC okuması JPA değişikliğini görsün

        assertThat(jdbc.queryForMap("SELECT status, completed, last_error FROM experiments WHERE id = ?", id))
                .containsEntry("status", "FAILED")
                .containsEntry("completed", 12)
                .hasEntrySatisfying("last_error", v -> assertThat((String) v).contains("yeniden başladı"));
    }

    @Test
    @DisplayName("hatalı parametre 400")
    void shouldRejectInvalidParameters() throws Exception {
        for (String body : List.of("{\"scenarios\":4}", "{\"scenarios\":101}", "{\"stopCount\":11}",
                "{\"stopCount\":2}", "{\"radiusM\":400}", "{\"repeats\":6}", "{\"seed\":-1}",
                "{\"seed\":2147483647}")) {
            start(body).andExpect(status().isBadRequest());
        }
        start("{\"vehicleId\":987654}").andExpect(status().isNotFound());
        verify(optimizer, never()).tourScenario(any());
        mvc.perform(get("/api/v1/compare/experiments/987654").header("Authorization", bearer("firma@test")))
                .andExpect(status().isNotFound());
    }
}
