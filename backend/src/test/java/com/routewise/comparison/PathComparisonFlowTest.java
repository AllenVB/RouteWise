package com.routewise.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.routewise.address.ServiceAreaRepository;
import com.routewise.address.ServiceAreaRepository.RoadNode;
import com.routewise.address.ServiceAreaRepository.SnapResult;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.PathAlgorithmOut;
import com.routewise.optimization.client.OptimizerDtos.PathCompareRequest;
import com.routewise.optimization.client.OptimizerDtos.PathCompareResult;
import com.routewise.support.ApiTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Seviye 1 karşılaştırması uçtan uca: gerçek filtre zinciri ve veritabanı;
 * optimizasyon servisi ve yol grafı sorguları (ways_vertices_pgr test şemasında
 * yok) taklit edilir.
 */
class PathComparisonFlowTest extends ApiTest {

    @MockitoBean
    private OptimizerClient optimizer;

    @MockitoBean
    private ServiceAreaRepository geo;

    @BeforeEach
    void seed() {
        user("firma@test", "COMPANY");
        when(geo.roadNode(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return id >= 900 ? null : new RoadNode(id, 27.97 + id / 10_000.0, 40.35);
        });
        when(geo.containsPoint(anyDouble(), anyDouble())).thenReturn(true);
        when(geo.nearestRoadNode(anyDouble(), anyDouble()))
                .thenReturn(new SnapResult(11L, 4.26)).thenReturn(new SnapResult(42L, 7.0));
        when(optimizer.comparePath(any())).thenAnswer(inv -> {
            PathCompareRequest r = inv.getArgument(0);
            return answer(r);
        });
    }

    private static PathCompareResult answer(PathCompareRequest r) {
        List<Long> best = List.of(r.fromNodeId(), 20L, r.toNodeId());
        List<double[]> coords = List.of(new double[] {27.97, 40.35}, new double[] {27.98, 40.35},
                new double[] {27.99, 40.35});
        return new PathCompareResult(r.fromNodeId(), r.toNodeId(), r.weight(), r.repeats(), List.of(
                new PathAlgorithmOut("DIJKSTRA", true, 1200, 140, 2, 850, 31.5, 0.0, true, best, coords),
                new PathAlgorithmOut("ASTAR", true, 1200, 140, 2, 210, 9.2, 0.0, true, best, coords),
                new PathAlgorithmOut("BFS", true, 1500, 170, 1, 600, 3.1, 25.0, false,
                        List.of(r.fromNodeId(), r.toNodeId()), coords.subList(0, 2)),
                new PathAlgorithmOut("DFS", true, 31_000, 3000, 140, 4000, 4.4, 2483.33, false, best, coords)));
    }

    private ResultActions compare(String body) throws Exception {
        return mvc.perform(post("/api/v1/compare/path").header("Authorization", bearer("firma@test"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("haritadaki iki nokta yola bağlanır, dört algoritma koşar, sonuç ve satırlar kaydedilir")
    void shouldSnapPointsRunAndStore() throws Exception {
        String id = compare("""
                {"from":{"lat":40.351,"lon":27.971},"to":{"lat":40.36,"lon":27.99}}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("PATH"))
                .andExpect(jsonPath("$.from.nodeId").value(11))
                .andExpect(jsonPath("$.from.snapDistanceM").value(4.3))
                .andExpect(jsonPath("$.to.nodeId").value(42))
                .andExpect(jsonPath("$.weight").value("DISTANCE"))
                .andExpect(jsonPath("$.repeats").value(5))
                .andExpect(jsonPath("$.results.length()").value(4))
                .andExpect(jsonPath("$.results[2].algorithm").value("BFS"))
                .andExpect(jsonPath("$.results[2].deviationPct").value(25.0))
                .andExpect(jsonPath("$.results[0].coordinates[0][0]").value(27.97))
                .andReturn().getResponse().getContentAsString().replaceAll(".*?\"id\":(\\d+).*", "$1");

        ArgumentCaptor<PathCompareRequest> sent = ArgumentCaptor.forClass(PathCompareRequest.class);
        verify(optimizer).comparePath(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new PathCompareRequest(11, 42, "distance", 5));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT algorithm, from_node_id, to_node_id, weight, found, path_length_m, nodes_visited,
                       deviation_pct, edge_count
                FROM path_comparison_runs WHERE comparison_id = ? ORDER BY id
                """, Long.valueOf(id));
        assertThat(rows).extracting(r -> r.get("algorithm")).containsExactly("DIJKSTRA", "ASTAR", "BFS", "DFS");
        assertThat(rows.get(3)).containsEntry("deviation_pct", new java.math.BigDecimal("2483.33"))
                .containsEntry("nodes_visited", 4000)
                .containsEntry("weight", "distance")
                .containsEntry("from_node_id", 11L);

        mvc.perform(get("/api/v1/comparisons/" + id).header("Authorization", bearer("firma@test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.valueOf(id)))
                .andExpect(jsonPath("$.from.nodeId").value(11))
                .andExpect(jsonPath("$.results[3].algorithm").value("DFS"))
                .andExpect(jsonPath("$.results[3].path.length()").value(3))
                .andExpect(jsonPath("$.results[1].samePathAsReference").value(true));
    }

    @Test
    @DisplayName("düğüm kimlikleriyle tekrarlanabilir deney: süre ağırlığı ve tekrar sayısı iletilir")
    void shouldAcceptNodeIdsWeightAndRepeats() throws Exception {
        compare("""
                {"fromNodeId":101,"toNodeId":202,"weight":"DURATION","repeats":3}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from.snapDistanceM").doesNotExist())
                .andExpect(jsonPath("$.from.lat").value(40.35))
                .andExpect(jsonPath("$.weight").value("DURATION"));

        verify(optimizer).comparePath(new PathCompareRequest(101, 202, "duration", 3));
        verify(geo, never()).nearestRoadNode(anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("hizmet alanı dışındaki nokta 422 OUTSIDE_SERVICE_AREA, servis çağrılmaz")
    void shouldRejectPointOutsideServiceArea() throws Exception {
        when(geo.containsPoint(anyDouble(), anyDouble())).thenReturn(false);

        compare("""
                {"from":{"lat":41.0,"lon":29.0},"toNodeId":202}
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTSIDE_SERVICE_AREA"));
        verify(optimizer, never()).comparePath(any());
    }

    @Test
    @DisplayName("hatalı girdi 400: graf dışı düğüm, eksik ya da çift uç, aynı düğüm, tekrar sınırı")
    void shouldRejectInvalidInput() throws Exception {
        compare("{\"fromNodeId\":101,\"toNodeId\":999}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        compare("{\"fromNodeId\":101}").andExpect(status().isBadRequest());
        compare("{\"fromNodeId\":101,\"from\":{\"lat\":40.35,\"lon\":27.97},\"toNodeId\":202}")
                .andExpect(status().isBadRequest());
        compare("{\"fromNodeId\":101,\"toNodeId\":101}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("aynı yol düğümüne")));
        compare("{\"fromNodeId\":101,\"toNodeId\":202,\"repeats\":21}").andExpect(status().isBadRequest());
        compare("{\"fromNodeId\":101,\"toNodeId\":202,\"repeats\":0}").andExpect(status().isBadRequest());

        verify(optimizer, never()).comparePath(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparisons", Integer.class)).isZero();
    }

    @Test
    @DisplayName("servis kapalıysa 502, yarım kayıt kalmaz")
    void shouldStoreNothingWhenOptimizerIsDown() throws Exception {
        doThrow(new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE, "kapalı")).when(optimizer).comparePath(any());

        compare("{\"fromNodeId\":101,\"toNodeId\":202}").andExpect(status().isBadGateway());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparisons", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM path_comparison_runs", Integer.class)).isZero();
    }

    @Test
    @DisplayName("olmayan karşılaştırma 404")
    void shouldReturn404ForUnknownComparison() throws Exception {
        mvc.perform(get("/api/v1/comparisons/987654").header("Authorization", bearer("firma@test")))
                .andExpect(status().isNotFound());
    }
}
