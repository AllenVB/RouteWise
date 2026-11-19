package com.routewise.comparison;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routewise.address.AddressDtos.Coordinate;
import com.routewise.address.ServiceAreaRepository;
import com.routewise.address.ServiceAreaRepository.RoadNode;
import com.routewise.address.ServiceAreaRepository.SnapResult;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.optimization.client.OptimizerClient;
import com.routewise.optimization.client.OptimizerDtos.PathAlgorithmOut;
import com.routewise.optimization.client.OptimizerDtos.PathCompareRequest;
import com.routewise.optimization.client.OptimizerDtos.PathCompareResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seviye 1 — yol bulma karşılaştırması (F8, roadmap phase 6 tasks 1–4).
 *
 * <p>Both ends come either as map points, snapped to the nearest road node with
 * the same query addresses use, or as road node ids (repeatable experiments).
 * The optimizer runs Dijkstra, A*, BFS and DFS on the same pair; the result is
 * stored whole for the screen and per algorithm for the analysis.
 */
@Service
public class PathComparisonService {

    private static final Logger log = LoggerFactory.getLogger(PathComparisonService.class);

    /** Each run is pure Python over ~10k nodes; 20 × 4 algorithms stays well inside the read timeout. */
    public static final int MAX_REPEATS = 20;
    static final int DEFAULT_REPEATS = 5;

    private final ServiceAreaRepository geo;
    private final OptimizerClient optimizer;
    private final ComparisonRepository comparisons;
    private final PathComparisonRunRepository runs;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PathComparisonService(ServiceAreaRepository geo, OptimizerClient optimizer,
                                 ComparisonRepository comparisons, PathComparisonRunRepository runs,
                                 ObjectMapper json, TransactionTemplate tx, Clock clock) {
        this.geo = geo;
        this.optimizer = optimizer;
        this.comparisons = comparisons;
        this.runs = runs;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    public enum Weight {
        DISTANCE, DURATION;

        String wire() {
            return name().toLowerCase();
        }
    }

    /** @param snapDistanceM how far the clicked point was from its road node; null when given as a node id */
    public record End(long nodeId, double lat, double lon, Double snapDistanceM) {}

    public record PathComparison(Long id, Comparison.Level level, Instant createdAt, End from, End to,
                                 Weight weight, int repeats, List<PathAlgorithmOut> results) {}

    record Params(End from, End to, Weight weight, int repeats) {}

    public PathComparison compare(Coordinate from, Long fromNodeId, Coordinate to, Long toNodeId,
                                  Weight weight, Integer repeats, Long userId) {
        End a = resolve("Başlangıç", from, fromNodeId);
        End b = resolve("Bitiş", to, toNodeId);
        if (a.nodeId() == b.nodeId()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Başlangıç ve bitiş aynı yol düğümüne düşüyor; birbirinden uzak iki nokta seç.");
        }
        Weight w = weight == null ? Weight.DISTANCE : weight;
        int n = repeats == null ? DEFAULT_REPEATS : repeats;
        if (n < 1 || n > MAX_REPEATS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tekrar sayısı 1 ile " + MAX_REPEATS + " arasında olmalı.");
        }

        // Optimizasyon servisi transaction dışında: hesaplama sürerken bağlantı tutulmaz.
        PathCompareResult result = optimizer.comparePath(new PathCompareRequest(a.nodeId(), b.nodeId(), w.wire(), n));
        Params params = new Params(a, b, w, n);
        Instant now = clock.instant();

        Comparison saved = tx.execute(status -> {
            Comparison c = comparisons.save(new Comparison(Comparison.Level.PATH, null, write(params), write(result),
                    userId, now));
            for (PathAlgorithmOut r : result.results()) {
                runs.save(new PathComparisonRun(c.getId(), r.algorithm(), a.nodeId(), b.nodeId(), w.wire(), r.found(),
                        r.found() ? scale(r.lengthM(), 2) : null, r.found() ? scale(r.durationS(), 1) : null,
                        r.found() ? r.edgeCount() : null, scale(r.computationTimeMs(), 3), r.nodesVisited(),
                        r.deviationPct() == null ? null : scale(r.deviationPct(), 2), now));
            }
            return c;
        });
        log.info("seviye 1 karsilastirma {}: {} -> {} ({}, {}x)", saved.getId(), a.nodeId(), b.nodeId(), w, n);
        return new PathComparison(saved.getId(), saved.getLevel(), saved.getCreatedAt(), a, b, w, n, result.results());
    }

    public PathComparison get(Long id) {
        Comparison c = comparisons.findById(id)
                .filter(x -> x.getLevel() == Comparison.Level.PATH)
                .orElseThrow(() -> ApiException.notFound("Karşılaştırma"));
        Params p = read(c.getParams(), Params.class);
        PathCompareResult r = read(c.getResult(), PathCompareResult.class);
        return new PathComparison(c.getId(), c.getLevel(), c.getCreatedAt(), p.from(), p.to(), p.weight(),
                p.repeats(), r.results());
    }

    private End resolve(String which, Coordinate point, Long nodeId) {
        if ((point == null) == (nodeId == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    which + " için ya haritadan bir nokta ya da bir yol düğümü verilmeli (ikisi birden değil).");
        }
        if (nodeId != null) {
            RoadNode node = geo.roadNode(nodeId);
            if (node == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, which + " düğümü yol grafında yok: " + nodeId);
            }
            return new End(nodeId, node.lat(), node.lon(), null);
        }
        if (!geo.containsPoint(point.lon(), point.lat())) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA, which + " noktası hizmet alanının dışında.");
        }
        SnapResult snap = geo.nearestRoadNode(point.lon(), point.lat());
        if (snap == null) {
            throw new ApiException(ErrorCode.TOO_FAR_FROM_ROAD, which + " noktasına yakın bir yol bulunamadı.");
        }
        RoadNode node = geo.roadNode(snap.nodeId());
        return new End(snap.nodeId(), node == null ? point.lat() : node.lat(), node == null ? point.lon() : node.lon(),
                Math.round(snap.distanceM() * 10) / 10.0);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("karsilastirma JSON'a yazilamadi", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("karsilastirma JSON'u okunamadi", e);
        }
    }

    private static BigDecimal scale(double v, int digits) {
        return BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP);
    }
}
