package com.routewise.routing.matrix;

import java.util.List;

import com.routewise.optimization.client.OptimizerCalls;
import com.routewise.optimization.client.OptimizerDtos.MatrixRequest;
import com.routewise.optimization.client.OptimizerDtos.MatrixResult;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls the Python service for a freshly computed matrix. */
@Component
public class MatrixClient {

    private final RestClient optimizerClient;

    public MatrixClient(RestClient optimizerClient) {
        this.optimizerClient = optimizerClient;
    }

    public DistanceMatrix fetch(List<Long> nodeIds) {
        MatrixResult r = OptimizerCalls.call("matris", () -> optimizerClient.post()
                .uri("/internal/matrix")
                .body(new MatrixRequest(nodeIds))
                .retrieve()
                .body(MatrixResult.class));
        // null hücre = ulaşılamaz; Jackson Double[][] içinde null'ı korur.
        return new DistanceMatrix(r.nodeSetHash(), r.nodeIds(), r.distanceM(), r.durationS(),
                r.unreachablePairs());
    }
}
