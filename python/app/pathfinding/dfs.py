"""
DFS — derinlik öncelikli arama.

Bulduğu **ilk** yolu döner; ne en kısa ne de en az kenarlı olması garanti edilir.
Şehir ağında bu yol çoğu zaman hedefe dolanarak varır — Seviye 1 tablosunda
"yol bulmak" ile "en iyi yolu bulmak" arasındaki farkı gösteren satırdır.

Özyineleme yerine açık yığın kullanılır: Python'un özyineleme sınırı ~1000'dir,
9.797 düğümlü grafta derin bir yol bunu kolayca aşar. Komşular CSR sırasıyla
gezilir; aynı girdi her zaman aynı yolu verir (tekrarlanabilir deney).
"""

from __future__ import annotations

import numpy as np

from ..graph.model import RoadGraph
from ._path import from_edges
from .base import PathResult, Timer, Weight


def find_path(
    graph: RoadGraph,
    source_id: int,
    target_id: int,
    weight: Weight = Weight.DISTANCE,   # imza diğerleriyle aynı; DFS ağırlık kullanmaz
) -> PathResult:
    s = graph.index_of(source_id)
    t = graph.index_of(target_id)
    n = graph.node_count
    done = np.zeros(n, dtype=bool)
    prev_node = np.full(n, -1, dtype=np.int64)
    prev_edge = np.full(n, -1, dtype=np.int64)

    # (düğüm, geldiği düğüm, geldiği kenar) — öncül, düğüm yığından çıkınca yazılır;
    # böylece yol gerçekten gezilen DFS ağacını izler.
    stack: list[tuple[int, int, int]] = [(s, -1, -1)]
    visited = 0
    found = False

    with Timer() as timer:
        while stack:
            u, parent, edge = stack.pop()
            if done[u]:
                continue
            done[u] = True
            prev_node[u] = parent
            prev_edge[u] = edge
            visited += 1
            if u == t:
                found = True
                break
            start, end = int(graph.indptr[u]), int(graph.indptr[u + 1])
            # Ters sırayla yığına: ilk komşu ilk gezilir.
            for i in range(end - 1, start - 1, -1):
                v = int(graph.neighbors[i])
                if not done[v]:
                    stack.append((v, u, i))

    if not found:
        return PathResult(found=False, nodes_visited=visited, computation_time_ms=timer.ms)
    return from_edges(graph, s, t, prev_node, prev_edge, visited, timer.ms)
