"""
Seviye 1 karşılaştırması — aynı kaynak-hedef çiftinde dört yol bulma algoritması.

Referans Dijkstra'dır (kanıtlı optimum). Her algoritma için:
  - bulduğu yolun gerçek uzunluğu ve süresi (aynı kenarlar üzerinden toplanır),
  - gezdiği düğüm sayısı (algoritmanın "ne kadar aradığı"),
  - hesaplama süresi: `repeats` koşunun medyanı — tek ölçüm işletim sistemi
    gürültüsüne açıktır, ortalama da tek bir yavaş koşuyla bozulur,
  - seçilen ağırlıkta Dijkstra'dan sapma (%).

Algoritmalar deterministiktir; yol ve düğüm sayısı ilk koşudan alınır, tekrarlar
yalnızca süreyi ölçer.
"""

from __future__ import annotations

import statistics
from collections.abc import Callable
from dataclasses import dataclass

from ..graph.model import RoadGraph
from . import astar, bfs, dfs, dijkstra
from .base import PathResult, Weight

PathFn = Callable[[RoadGraph, int, int, Weight], PathResult]

REFERENCE = "DIJKSTRA"


@dataclass
class AlgorithmRun:
    algorithm: str
    result: PathResult
    median_ms: float
    deviation_pct: float | None
    same_path_as_reference: bool


def _algorithms(graph: RoadGraph, weight: Weight) -> dict[str, PathFn]:
    speed = astar.max_speed_mps(graph) if weight is Weight.DURATION else None
    return {
        "DIJKSTRA": dijkstra.shortest_path,
        "ASTAR": lambda g, s, t, w: astar.shortest_path(g, s, t, w, speed_mps=speed),
        "BFS": bfs.find_path,
        "DFS": dfs.find_path,
    }


def compare_paths(graph: RoadGraph, source_id: int, target_id: int,
                  weight: Weight = Weight.DISTANCE, repeats: int = 5) -> list[AlgorithmRun]:
    """
    KeyError: düğüm grafta yok. Yol yoksa (ayrık bileşen) found=False döner,
    sapma hesaplanmaz.
    """
    if repeats < 1:
        raise ValueError("repeats en az 1 olmalı")
    runs: list[tuple[str, PathResult, float]] = []
    for name, fn in _algorithms(graph, weight).items():
        first = fn(graph, source_id, target_id, weight)
        times = [first.computation_time_ms]
        times += [fn(graph, source_id, target_id, weight).computation_time_ms for _ in range(repeats - 1)]
        runs.append((name, first, statistics.median(times)))

    ref = next(r for name, r, _ in runs if name == REFERENCE)
    ref_cost = _cost(ref, weight)
    out: list[AlgorithmRun] = []
    for name, r, ms in runs:
        deviation = None
        if r.found and ref.found:
            deviation = 0.0 if ref_cost == 0 else (_cost(r, weight) - ref_cost) / ref_cost * 100.0
        out.append(AlgorithmRun(name, r, ms, deviation, r.found and ref.found and r.path == ref.path))
    return out


def _cost(r: PathResult, weight: Weight) -> float:
    return r.length_m if weight is Weight.DISTANCE else r.duration_s
