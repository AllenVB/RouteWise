package com.routewise.optimization.client;

import java.util.List;

import com.routewise.optimization.client.OptimizerDtos.GeometryRequest;
import com.routewise.optimization.client.OptimizerDtos.GeometryResult;
import com.routewise.optimization.client.OptimizerDtos.OptimizeRequest;
import com.routewise.optimization.client.OptimizerDtos.OptimizeResult;
import com.routewise.optimization.client.OptimizerDtos.PathCompareRequest;
import com.routewise.optimization.client.OptimizerDtos.PathCompareResult;
import com.routewise.optimization.client.OptimizerDtos.TourCompareRequest;
import com.routewise.optimization.client.OptimizerDtos.TourCompareResult;
import com.routewise.optimization.client.OptimizerDtos.PairedRequest;
import com.routewise.optimization.client.OptimizerDtos.PairedResult;
import com.routewise.optimization.client.OptimizerDtos.TourScenarioRequest;
import com.routewise.optimization.client.OptimizerDtos.TourScenarioResult;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls the stateless optimizer. It never touches the database; everything travels in the request. */
@Component
public class OptimizerClient {

    private final RestClient optimizerClient;

    public OptimizerClient(RestClient optimizerClient) {
        this.optimizerClient = optimizerClient;
    }

    public OptimizeResult optimize(OptimizeRequest request) {
        return OptimizerCalls.call("optimize", () -> optimizerClient.post()
                .uri("/internal/optimize")
                .body(request)
                .retrieve()
                .body(OptimizeResult.class));
    }

    /** Level 1: Dijkstra, A*, BFS and DFS on the same pair of road nodes. */
    public PathCompareResult comparePath(PathCompareRequest request) {
        return OptimizerCalls.call("yol karsilastirma", () -> optimizerClient.post()
                .uri("/internal/compare/path")
                .body(request)
                .retrieve()
                .body(PathCompareResult.class));
    }

    /** Level 2: NAIVE, GREEDY_NN, SAVINGS, ROUTEWISE and, up to 10 stops, DFS_EXHAUSTIVE. */
    public TourCompareResult compareTour(TourCompareRequest request) {
        return OptimizerCalls.call("tur karsilastirma", () -> optimizerClient.post()
                .uri("/internal/compare/tour")
                .body(request)
                .retrieve()
                .body(TourCompareResult.class));
    }

    /** Builds one synthetic scenario from its seed and runs every tour algorithm on it. */
    public TourScenarioResult tourScenario(TourScenarioRequest request) {
        return OptimizerCalls.call("deney senaryosu", () -> optimizerClient.post()
                .uri("/internal/experiments/tour-scenario")
                .body(request)
                .retrieve()
                .body(TourScenarioResult.class));
    }

    /** Wilcoxon signed-rank and paired t-test, one-sided, per candidate/reference pair. */
    public PairedResult pairedTests(PairedRequest request) {
        return OptimizerCalls.call("anlamlilik testi", () -> optimizerClient.post()
                .uri("/internal/analysis/paired")
                .body(request)
                .retrieve()
                .body(PairedResult.class));
    }

    /** Road-following coordinates for a visiting order, depot at both ends. */
    public GeometryResult routeGeometry(List<Long> nodeIds) {
        return OptimizerCalls.call("geometri", () -> optimizerClient.post()
                .uri("/internal/route-geometry")
                .body(new GeometryRequest(nodeIds))
                .retrieve()
                .body(GeometryResult.class));
    }
}
