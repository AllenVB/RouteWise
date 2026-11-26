"""
Yol bulma algoritmalarının paylaştığı küçük yardımcılar.

Her algoritma "hangi kenardan geldim" bilgisini (prev_edge) tutar; yolun düğümleri,
uzunluğu ve süresi o kenarlar üzerinden aynı şekilde toplanır. Böylece BFS ile
DFS'in bulduğu yolun gerçek uzunluğu, Dijkstra'nınkiyle birebir aynı ölçüyle
karşılaştırılır.
"""

from __future__ import annotations

import numpy as np

from ..graph.model import RoadGraph
from .base import PathResult


def from_edges(graph: RoadGraph, s: int, t: int, prev_node: np.ndarray, prev_edge: np.ndarray,
               visited: int, ms: float) -> PathResult:
    """Hedeften kaynağa geri yürüyerek yolu kurar; hedefe varılmadıysa found=False."""
    if t != s and prev_edge[t] < 0:
        return PathResult(found=False, nodes_visited=visited, computation_time_ms=ms)
    nodes: list[int] = [t]
    length = 0.0
    duration = 0.0
    cur = t
    while cur != s:
        e = int(prev_edge[cur])
        length += float(graph.length_m[e])
        duration += float(graph.duration_s[e])
        cur = int(prev_node[cur])
        nodes.append(cur)
    nodes.reverse()
    return PathResult(
        found=True,
        path=[int(graph.node_ids[i]) for i in nodes],
        length_m=length,
        duration_s=duration,
        nodes_visited=visited,
        computation_time_ms=ms,
    )
