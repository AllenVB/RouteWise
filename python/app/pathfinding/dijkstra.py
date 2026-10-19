"""
Dijkstra — en kısa yol.

Projenin referans optimumudur: A*, BFS ve DFS bunun sonucuna göre ölçülür.
Doğruluğu pgRouting'in `pgr_dijkstra` fonksiyonuyla sınanır; aynı girdide aynı
mesafeyi vermek zorundadır.

Uygulama notu: graf CSR düzeninde tutulduğu için komşular bitişik dizilerden
okunur. `heapq` ile tembel silme (lazy deletion) kullanılır — ziyaret edilmiş
düğüm yığından çıkarıldığında atlanır; azalt-anahtar işlemine gerek kalmaz.
"""

from __future__ import annotations

import heapq

import numpy as np

from ..graph.model import RoadGraph
from .base import PathResult, Timer, Weight, edge_weights


def shortest_path(
    graph: RoadGraph,
    source_id: int,
    target_id: int,
    weight: Weight = Weight.DISTANCE,
) -> PathResult:
    """Tek kaynaktan tek hedefe en kısa yol."""
    s = graph.index_of(source_id)
    t = graph.index_of(target_id)
    w = edge_weights(graph, weight)
    other = graph.duration_s if weight is Weight.DISTANCE else graph.length_m

    n = graph.node_count
    dist = np.full(n, np.inf)
    alt = np.zeros(n)           # diğer ölçü (mesafe seçildiyse süre, tersi de)
    prev = np.full(n, -1, dtype=np.int64)
    done = np.zeros(n, dtype=bool)

    dist[s] = 0.0
    heap: list[tuple[float, int]] = [(0.0, s)]
    visited = 0

    with Timer() as timer:
        while heap:
            d, u = heapq.heappop(heap)
            if done[u]:
                continue          # tembel silme: eski, geçersiz giriş
            done[u] = True
            visited += 1
            if u == t:
                break

            start, end = graph.indptr[u], graph.indptr[u + 1]
            for i in range(start, end):
                v = graph.neighbors[i]
                if done[v]:
                    continue
                nd = d + w[i]
                if nd < dist[v]:
                    dist[v] = nd
                    alt[v] = alt[u] + other[i]
                    prev[v] = u
                    heapq.heappush(heap, (nd, int(v)))

    if not np.isfinite(dist[t]):
        return PathResult(found=False, nodes_visited=visited,
                          computation_time_ms=timer.ms)

    path_idx: list[int] = []
    cur = t
    while cur != -1:
        path_idx.append(int(cur))
        cur = int(prev[cur])
    path_idx.reverse()

    length = dist[t] if weight is Weight.DISTANCE else alt[t]
    duration = alt[t] if weight is Weight.DISTANCE else dist[t]

    return PathResult(
        found=True,
        path=[int(graph.node_ids[i]) for i in path_idx],
        length_m=float(length),
        duration_s=float(duration),
        nodes_visited=visited,
        computation_time_ms=timer.ms,
    )


def single_source(
    graph: RoadGraph,
    source_id: int,
    weight: Weight = Weight.DISTANCE,
) -> tuple[np.ndarray, np.ndarray]:
    """
    Tek kaynaktan TÜM düğümlere mesafe ve süre.

    Matris üretiminde kullanılır: N×N matris için N kez tek-kaynak koşmak,
    N² kez kaynak-hedef koşmaktan kat kat ucuzdur.

    Döner: (mesafe_m[n], süre_s[n]) — ulaşılamayan düğümler için inf.
    """
    s = graph.index_of(source_id)
    w = edge_weights(graph, weight)
    other = graph.duration_s if weight is Weight.DISTANCE else graph.length_m

    n = graph.node_count
    dist = np.full(n, np.inf)
    alt = np.full(n, np.inf)
    done = np.zeros(n, dtype=bool)

    dist[s] = 0.0
    alt[s] = 0.0
    heap: list[tuple[float, int]] = [(0.0, s)]

    while heap:
        d, u = heapq.heappop(heap)
        if done[u]:
            continue
        done[u] = True
        start, end = graph.indptr[u], graph.indptr[u + 1]
        for i in range(start, end):
            v = graph.neighbors[i]
            if done[v]:
                continue
            nd = d + w[i]
            if nd < dist[v]:
                dist[v] = nd
                alt[v] = alt[u] + other[i]
                heapq.heappush(heap, (nd, int(v)))

    if weight is Weight.DISTANCE:
        return dist, alt
    return alt, dist
