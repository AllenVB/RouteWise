"""
A* — hedefe yönelik en kısa yol.

Dijkstra ile aynı uzunlukta yolu bulmak zorundadır; farkı daha az düğüm gezmesidir.
Sezgisel (heuristic) olarak düğümden hedefe kuş uçuşu (haversine) mesafe kullanılır.
Bir yolun gerçek uzunluğu iki ucu arasındaki kuş uçuşu mesafeden kısa olamayacağı
için sezgisel **kabul edilebilir** (admissible) ve tutarlıdır: kapanan düğüm bir
daha açılmaz, sonuç optimumdur.

Süre ağırlığında sezgisel = kuş uçuşu / graftaki en yüksek hız. Bu da gerçek süreyi
aşamaz; ama hız farkları büyük olduğundan yönlendirmesi zayıflar — A*'ın süre
ağırlığında daha az kazandırması beklenen bir sonuçtur.
"""

from __future__ import annotations

import heapq
import math

import numpy as np

from ..graph.model import RoadGraph
from ._path import from_edges
from .base import PathResult, Timer, Weight, edge_weights

EARTH_RADIUS_M = 6_371_008.8
# Küre üzerindeki haversine, elipsoit üzerinde ölçülen kenar uzunluğunu düz bir
# kenarda binde birkaç aşabilir; bu da sezgiseli kabul edilemez yapar. Küçük bir
# pay sezgiseli güvenli tarafta tutar, yönlendirmeden pratikte bir şey kaybettirmez.
HEURISTIC_SAFETY = 0.99


def max_speed_mps(graph: RoadGraph) -> float:
    """Graftaki en yüksek kenar hızı (m/s) — süre sezgiselinin paydası."""
    ok = graph.duration_s > 0
    if not np.any(ok):
        return math.inf
    return float(np.max(graph.length_m[ok] / graph.duration_s[ok]))


def shortest_path(
    graph: RoadGraph,
    source_id: int,
    target_id: int,
    weight: Weight = Weight.DISTANCE,
    speed_mps: float | None = None,
) -> PathResult:
    """
    speed_mps: süre ağırlığında sezgiselin hızı; verilmezse graftan hesaplanır.
    Bu bir ön işlemdir (graf değişmedikçe sabittir) ve süre ölçümüne katılmaz.
    """
    s = graph.index_of(source_id)
    t = graph.index_of(target_id)
    w = edge_weights(graph, weight)
    divisor = 1.0
    if weight is Weight.DURATION:
        divisor = speed_mps if speed_mps is not None else max_speed_mps(graph)

    lat = graph.node_lat
    lon = graph.node_lon
    t_lat = math.radians(float(lat[t]))
    t_lon = math.radians(float(lon[t]))
    cos_t = math.cos(t_lat)

    def h(i: int) -> float:
        la = math.radians(float(lat[i]))
        dlat = la - t_lat
        dlon = math.radians(float(lon[i])) - t_lon
        a = math.sin(dlat / 2) ** 2 + math.cos(la) * cos_t * math.sin(dlon / 2) ** 2
        return HEURISTIC_SAFETY * 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a))) / divisor

    n = graph.node_count
    g = np.full(n, np.inf)
    prev_node = np.full(n, -1, dtype=np.int64)
    prev_edge = np.full(n, -1, dtype=np.int64)
    done = np.zeros(n, dtype=bool)

    g[s] = 0.0
    heap: list[tuple[float, float, int]] = [(h(s), 0.0, s)]
    visited = 0

    with Timer() as timer:
        while heap:
            _, d, u = heapq.heappop(heap)
            if done[u]:
                continue
            done[u] = True
            visited += 1
            if u == t:
                break
            for i in range(graph.indptr[u], graph.indptr[u + 1]):
                v = int(graph.neighbors[i])
                if done[v]:
                    continue
                nd = d + w[i]
                if nd < g[v]:
                    g[v] = nd
                    prev_node[v] = u
                    prev_edge[v] = i
                    heapq.heappush(heap, (nd + h(v), nd, v))

    if not done[t]:
        return PathResult(found=False, nodes_visited=visited, computation_time_ms=timer.ms)
    return from_edges(graph, s, t, prev_node, prev_edge, visited, timer.ms)
