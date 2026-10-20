package com.routewise.routing.matrix;

import java.util.List;

/**
 * Distance and duration between every pair in a node set.
 *
 * <p>Rows and columns follow {@link #nodeIds}, which is sorted and de-duplicated,
 * so index lookups are stable across calls and match the cache key.
 *
 * <p>A {@code null} cell means <b>unreachable</b>. It is deliberately not a large
 * number: a sentinel would let the optimizer treat the pair as "expensive but
 * drivable" and produce a route that cannot be driven.
 */
public record DistanceMatrix(
        String nodeSetHash,
        List<Long> nodeIds,
        Double[][] distanceM,
        Double[][] durationS,
        int unreachablePairs) {

    public int size() {
        return nodeIds.size();
    }

    public int indexOf(long nodeId) {
        int i = nodeIds.indexOf(nodeId);
        if (i < 0) {
            throw new IllegalArgumentException("düğüm matriste yok: " + nodeId);
        }
        return i;
    }

    public Double distance(long from, long to) {
        return distanceM[indexOf(from)][indexOf(to)];
    }

    public Double duration(long from, long to) {
        return durationS[indexOf(from)][indexOf(to)];
    }
}
