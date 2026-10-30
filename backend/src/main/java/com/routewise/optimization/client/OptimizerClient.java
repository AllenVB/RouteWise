package com.routewise.optimization.client;

import java.util.List;

import com.routewise.optimization.client.OptimizerDtos.GeometryRequest;
import com.routewise.optimization.client.OptimizerDtos.GeometryResult;
import com.routewise.optimization.client.OptimizerDtos.OptimizeRequest;
import com.routewise.optimization.client.OptimizerDtos.OptimizeResult;

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

    /** Road-following coordinates for a visiting order, depot at both ends. */
    public GeometryResult routeGeometry(List<Long> nodeIds) {
        return OptimizerCalls.call("geometri", () -> optimizerClient.post()
                .uri("/internal/route-geometry")
                .body(new GeometryRequest(nodeIds))
                .retrieve()
                .body(GeometryResult.class));
    }
}
