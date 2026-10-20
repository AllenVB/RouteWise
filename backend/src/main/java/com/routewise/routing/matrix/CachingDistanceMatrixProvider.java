package com.routewise.routing.matrix;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the matrix from the database, computing it only on a miss.
 *
 * <p>Without this cache the system is unusable while the algorithm is being
 * tuned: a metaheuristic needs the matrix once per run, but tuning means
 * hundreds of runs over the same stop set. The key is the node set, so an
 * identical set never pays twice.
 *
 * <p><b>Unreachable pairs are not stored.</b> The cache columns are NOT NULL and
 * a sentinel distance would be worse than useless — the optimizer would read it
 * as "expensive but drivable". A cell absent from the cached rows therefore
 * means unreachable, and is read back as {@code null}.
 */
@Service
public class CachingDistanceMatrixProvider implements DistanceMatrixProvider {

    private static final Logger log = LoggerFactory.getLogger(CachingDistanceMatrixProvider.class);

    private final MatrixCacheRepository repository;
    private final MatrixClient client;

    public CachingDistanceMatrixProvider(MatrixCacheRepository repository, MatrixClient client) {
        this.repository = repository;
        this.client = client;
    }

    @Override
    @Transactional
    public DistanceMatrix forNodes(List<Long> nodeIds) {
        String hash = NodeSetHash.of(nodeIds);

        List<MatrixCacheEntry> cached = repository.findByNodeSetHash(hash);
        if (!cached.isEmpty()) {
            log.debug("matris cache isabet: {} ({} hücre)", hash.substring(0, 12), cached.size());
            return fromCache(hash, nodeIds, cached);
        }

        log.info("matris cache ıska: {} — hesaplanıyor", hash.substring(0, 12));
        DistanceMatrix matrix = client.fetch(nodeIds);
        store(matrix);
        return matrix;
    }

    private static DistanceMatrix fromCache(String hash, List<Long> requested,
                                            List<MatrixCacheEntry> rows) {
        List<Long> ids = requested.stream().distinct().sorted().toList();
        int n = ids.size();
        Double[][] dist = new Double[n][n];
        Double[][] dur = new Double[n][n];

        for (MatrixCacheEntry e : rows) {
            int i = ids.indexOf(e.getFromNodeId());
            int j = ids.indexOf(e.getToNodeId());
            if (i < 0 || j < 0) {
                continue;   // küme değişmişse ilgisiz satır; hash eşleştiği için beklenmez
            }
            dist[i][j] = e.getDistanceM();
            dur[i][j] = e.getDurationS().doubleValue();
        }

        int unreachable = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (dist[i][j] == null) {
                    unreachable++;   // yazılmamış hücre = yol yok
                }
            }
        }
        return new DistanceMatrix(hash, ids, dist, dur, unreachable);
    }

    private void store(DistanceMatrix m) {
        List<MatrixCacheEntry> rows = new ArrayList<>();
        int n = m.size();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Double d = m.distanceM()[i][j];
                Double s = m.durationS()[i][j];
                if (d == null || s == null) {
                    continue;   // ulaşılamaz: satır yazılmaz
                }
                rows.add(new MatrixCacheEntry(
                        m.nodeSetHash(), m.nodeIds().get(i), m.nodeIds().get(j),
                        d, (int) Math.round(s)));
            }
        }
        repository.saveAll(rows);
        log.info("matris cache'lendi: {} hücre ({} ulaşılamaz atlandı)",
                rows.size(), n * n - rows.size());
    }
}
