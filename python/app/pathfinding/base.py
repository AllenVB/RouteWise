"""
Yol bulma algoritmalarının ortak arayüzü.

Seviye 1 karşılaştırması (Dijkstra / A* / BFS / DFS) ancak hepsi aynı metrikleri
aynı biçimde raporlarsa adil olur. Bu yüzden sonuç tipi ve ölçüm burada tek
yerde tanımlanır; her algoritma onu doldurur.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum

import numpy as np

from ..graph.model import RoadGraph


class Weight(str, Enum):
    """Kenar ağırlığı olarak ne kullanılacak."""

    DISTANCE = "distance"   # metre
    DURATION = "duration"   # saniye


@dataclass
class PathResult:
    """Tek bir kaynak-hedef sorgusunun sonucu ve ölçümleri."""

    found: bool
    # Düğüm kimlikleri (graf indeksi değil) — dışarıya kimlik verilir.
    path: list[int] = field(default_factory=list)
    length_m: float = 0.0
    duration_s: float = 0.0
    nodes_visited: int = 0
    computation_time_ms: float = 0.0

    def as_dict(self) -> dict:
        return {
            "found": self.found,
            "path": self.path,
            "lengthM": round(self.length_m, 2),
            "durationS": round(self.duration_s, 1),
            "nodesVisited": self.nodes_visited,
            "computationTimeMs": round(self.computation_time_ms, 3),
        }


def edge_weights(graph: RoadGraph, weight: Weight) -> np.ndarray:
    return graph.length_m if weight is Weight.DISTANCE else graph.duration_s


class Timer:
    """Algoritmaların hesaplama süresini aynı şekilde ölçmesi için."""

    def __enter__(self) -> "Timer":
        self._t0 = time.perf_counter()
        return self

    def __exit__(self, *exc) -> None:
        self.ms = (time.perf_counter() - self._t0) * 1000.0
