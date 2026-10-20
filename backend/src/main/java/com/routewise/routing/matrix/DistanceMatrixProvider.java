package com.routewise.routing.matrix;

import java.util.List;

/**
 * Supplies the distance/duration matrix for a set of graph nodes.
 *
 * <p>Kept behind an interface so the source can change without touching the
 * algorithm or domain code. Today the only implementation caches in the database
 * and asks the Python service on a miss.
 */
public interface DistanceMatrixProvider {

    DistanceMatrix forNodes(List<Long> nodeIds);
}
