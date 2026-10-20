package com.routewise.routing.matrix;

import java.util.List;
import java.util.Map;

import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Calls the Python service for a freshly computed matrix. */
@Component
public class MatrixClient {

    private static final Logger log = LoggerFactory.getLogger(MatrixClient.class);

    private final RestClient optimizerClient;

    public MatrixClient(RestClient optimizerClient) {
        this.optimizerClient = optimizerClient;
    }

    public DistanceMatrix fetch(List<Long> nodeIds) {
        try {
            Map<String, Object> body = optimizerClient.post()
                    .uri("/internal/matrix")
                    .body(Map.of("nodeIds", nodeIds))
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                throw new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE,
                        "Rota servisi boş yanıt döndürdü.");
            }
            return map(body);

        } catch (ResourceAccessException e) {
            // Bağlantı kurulamadı veya okuma zaman aşımına uğradı.
            log.warn("matris servisi erişilemedi: {}", e.getMessage());
            throw new ApiException(ErrorCode.OPTIMIZER_TIMEOUT,
                    "Rota hesaplama servisine ulaşılamadı. Biraz sonra tekrar dene.", e);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("matris servisi hatası: {}", e.getMessage());
            throw new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE,
                    "Rota hesaplama servisi şu an yanıt veremiyor.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static DistanceMatrix map(Map<String, Object> body) {
        List<Number> ids = (List<Number>) body.get("nodeIds");
        List<Long> nodeIds = ids.stream().map(Number::longValue).toList();
        int n = nodeIds.size();

        return new DistanceMatrix(
                (String) body.get("nodeSetHash"),
                nodeIds,
                grid((List<List<Number>>) body.get("distanceM"), n),
                grid((List<List<Number>>) body.get("durationS"), n),
                ((Number) body.getOrDefault("unreachablePairs", 0)).intValue());
    }

    private static Double[][] grid(List<List<Number>> rows, int n) {
        Double[][] out = new Double[n][n];
        for (int i = 0; i < n; i++) {
            List<Number> row = rows.get(i);
            for (int j = 0; j < n; j++) {
                Number v = row.get(j);
                out[i][j] = v == null ? null : v.doubleValue();   // null = ulaşılamaz
            }
        }
        return out;
    }
}
