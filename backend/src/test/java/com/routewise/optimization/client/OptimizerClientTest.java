package com.routewise.optimization.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.client.OptimizerDtos.CostParamsIn;
import com.routewise.optimization.client.OptimizerDtos.MatrixIn;
import com.routewise.optimization.client.OptimizerDtos.OptimizeRequest;
import com.routewise.optimization.client.OptimizerDtos.StopIn;
import com.routewise.optimization.client.OptimizerDtos.VehicleIn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Wire contract with the Python service and the error mapping. */
class OptimizerClientTest {

    private MockRestServiceServer server;
    private OptimizerClient client;

    private static final OptimizeRequest REQUEST = new OptimizeRequest("GREEDY_NN", 10L,
            List.of(new StopIn(501L, 11L, 2.0, 300.0, 0)),
            List.of(new VehicleIn(1L, 100.0, 9.5, 250.0)),
            new MatrixIn(List.of(10L, 11L),
                    new Double[][] {{0.0, 100.0}, {null, 0.0}},
                    new Double[][] {{0.0, 10.0}, {null, 0.0}}),
            new CostParamsIn(44.0, 150.0, 2.5), null, null, 32_400.0);

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://optimizer");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OptimizerClient(builder.build());
    }

    @Test
    @DisplayName("İstek Python şemasıyla aynı alan adlarını taşır; null hücre null kalır")
    void sendsPythonFieldNames() {
        server.expect(requestTo("http://optimizer/internal/optimize"))
                .andExpect(jsonPath("$.depotNodeId").value(10))
                .andExpect(jsonPath("$.stops[0].serviceTimeS").value(300.0))
                .andExpect(jsonPath("$.vehicles[0].capacityKg").value(100.0))
                .andExpect(jsonPath("$.matrix.distanceM[1][0]").doesNotExist())
                .andExpect(jsonPath("$.vehicles[0].fuelLPer100km").value(9.5))
                .andExpect(jsonPath("$.costParams.priorityPenalty").value(2.5))
                .andExpect(jsonPath("$.maxRouteDurationS").value(32_400.0))
                .andRespond(withSuccess("""
                        {"randomSeed": null,
                         "result": {"algorithm": "GREEDY_NN", "routes": [], "totalDistanceM": 0,
                                    "totalDurationS": 0, "vehicleCount": 0, "computationTimeMs": 0.1,
                                    "totalFuelL": 0, "totalCostTry": 0, "priorityPenalty": 0, "objective": 0},
                         "baseline": {"algorithm": "NAIVE", "routes": [], "totalDistanceM": 0,
                                    "totalDurationS": 0, "vehicleCount": 0, "computationTimeMs": 0.1,
                                    "totalFuelL": 0, "totalCostTry": 0, "priorityPenalty": 0, "objective": 0},
                         "unassigned": [{"stopId": 501, "reason": "UNREACHABLE"}]}
                        """, MediaType.APPLICATION_JSON));

        var r = client.optimize(REQUEST);

        assertThat(r.unassigned()).singleElement().satisfies(u -> assertThat(u.stopId()).isEqualTo(501L));
        server.verify();
    }

    @Test
    @DisplayName("409 NO_FEASIBLE_SOLUTION'a çevrilir, optimizerin mesajı korunur")
    void conflictBecomesNoFeasibleSolution() {
        server.expect(requestTo("http://optimizer/internal/optimize"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"detail": {"code": "NO_FEASIBLE_SOLUTION",
                                            "message": "Toplam yük 1600.00 kg, araç kapasitesi 1500.00 kg."}}
                                """));

        assertThatThrownBy(() -> client.optimize(REQUEST))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("1600.00 kg")
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NO_FEASIBLE_SOLUTION);
    }

    @Test
    @DisplayName("422 bizim hatamızdır: kullanıcıya kısıt ihlali gibi gösterilmez")
    void unprocessableIsInternalError() {
        server.expect(requestTo("http://optimizer/internal/optimize"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": [{\"msg\": \"matriste olmayan düğümler\"}]}"));

        assertThatThrownBy(() -> client.optimize(REQUEST))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("Okuma zaman aşımı OPTIMIZER_TIMEOUT (504)")
    void readTimeout() {
        server.expect(requestTo("http://optimizer/internal/optimize"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.optimize(REQUEST))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.OPTIMIZER_TIMEOUT);
    }

    @Test
    @DisplayName("Bağlantı reddi OPTIMIZER_UNAVAILABLE (502) — eskiden yanlışlıkla 504'tü")
    void connectionRefused() {
        server.expect(requestTo("http://optimizer/internal/optimize"))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.optimize(REQUEST))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.OPTIMIZER_UNAVAILABLE);
    }

    @Test
    @DisplayName("Geometri yanıtı [boylam, enlem] çiftleri olarak okunur")
    void readsGeometry() {
        server.expect(requestTo("http://optimizer/internal/route-geometry"))
                .andExpect(jsonPath("$.nodeIds[0]").value(10))
                .andRespond(withSuccess("""
                        {"legs": [{"fromNodeId": 10, "toNodeId": 11, "lengthM": 100.0, "durationS": 10.0,
                                   "coordinates": [[27.97, 40.35], [27.98, 40.36]]}],
                         "totalLengthM": 100.0, "computationTimeMs": 1.0}
                        """, MediaType.APPLICATION_JSON));

        var g = client.routeGeometry(List.of(10L, 11L));

        assertThat(g.legs().get(0).coordinates().get(1)).containsExactly(27.98, 40.36);
    }
}
