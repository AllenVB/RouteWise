"""
BFS — genişlik öncelikli arama.

Kenar ağırlıklarını **yok sayar**: en az kenarla (kavşak sayısıyla) hedefe varan
yolu bulur. Kenar uzunlukları çok farklı olan bir şehir ağında bu yol metre
cinsinden en kısa yol değildir. Karşılaştırmada gösterilen şey tam olarak budur:
BFS'in bulduğu yolun gerçek uzunluğu Dijkstra'nınkinden yüzde kaç sapıyor.
"""

from __future__ import annotations

from collections import deque

import numpy as np

from ..graph.model import RoadGraph
from ._path import from_edges
from .base import PathResult, Timer, Weight


def find_path(
    graph: RoadGraph,
    source_id: int,
    target_id: int,
    weight: Weight = Weight.DISTANCE,   # imza diğerleriyle aynı; BFS ağırlık kullanmaz
) -> PathResult:
    s = graph.index_of(source_id)
    t = graph.index_of(target_id)
    n = graph.node_count
    seen = np.zeros(n, dtype=bool)
    prev_node = np.full(n, -1, dtype=np.int64)
    prev_edge = np.full(n, -1, dtype=np.int64)

    seen[s] = True
    queue = deque([s])
    visited = 0
    found = False

    with Timer() as timer:
        while queue:
            u = queue.popleft()
            visited += 1
            if u == t:
                found = True
                break
            for i in range(graph.indptr[u], graph.indptr[u + 1]):
                v = int(graph.neighbors[i])
                if not seen[v]:
                    seen[v] = True
                    prev_node[v] = u
                    prev_edge[v] = i
                    queue.append(v)

    if not found:
        return PathResult(found=False, nodes_visited=visited, computation_time_ms=timer.ms)
    return from_edges(graph, s, t, prev_node, prev_edge, visited, timer.ms)
