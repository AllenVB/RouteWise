"""
VRP veri yapıları — problem girdisi ve çözüm.

Problem içinde her şey **problem indeksiyle** konuşur: 0 depo, 1..n duraklar
(istekteki sırayla). Matris backend'den düğüm kimliği sırasıyla gelir; burada
bir kez problem indeksine çevrilir. Böylece algoritmalar düğüm kimliği aramakla
uğraşmaz ve aynı düğüme düşen iki kargo (aynı apartman) doğal olarak aralarında
0 m olan iki ayrı durak olur.

Ulaşılamaz çift `inf` ile temsil edilir, büyük bir sayıyla değil
(bkz. `matrix/builder.py:_json_safe`).
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass, field

import numpy as np

DEPOT = 0


@dataclass(frozen=True)
class Stop:
    id: int                   # backend'deki kargo kimliği — yanıtta geri döner
    node_id: int
    demand_kg: float
    service_time_s: float = 0.0
    priority: int = 0


@dataclass(frozen=True)
class Vehicle:
    id: int
    capacity_kg: float


@dataclass(frozen=True)
class Problem:
    depot_node_id: int
    stops: tuple[Stop, ...]
    vehicles: tuple[Vehicle, ...]
    distance: np.ndarray      # float64[n+1, n+1] — metre, inf = yol yok
    duration: np.ndarray      # float64[n+1, n+1] — saniye

    @property
    def size(self) -> int:
        """Durak sayısı (depo hariç)."""
        return len(self.stops)

    def stop(self, index: int) -> Stop:
        """Problem indeksinden (1..n) durağa."""
        # Açık sınır kontrolü: stop(0) aksi halde stops[-1]'i, yani son durağı
        # sessizce döndürür ve depo bir kargo gibi yük taşır görünür.
        if not 1 <= index <= len(self.stops):
            raise IndexError(f"durak indeksi 1..{len(self.stops)} olmalı, {index} geldi")
        return self.stops[index - 1]

    def reachable(self, a: int, b: int) -> bool:
        return math.isfinite(self.distance[a, b]) and math.isfinite(self.duration[a, b])

    @classmethod
    def from_node_matrix(
        cls,
        node_ids: Sequence[int],
        distance_m: Sequence[Sequence[float | None]],
        duration_s: Sequence[Sequence[float | None]],
        depot_node_id: int,
        stops: Sequence[Stop],
        vehicles: Sequence[Vehicle],
    ) -> Problem:
        """Düğüm sıralı matristen problem indeksli matrise."""
        pos = {int(n): i for i, n in enumerate(node_ids)}
        order = [depot_node_id, *(s.node_id for s in stops)]
        idx = np.array([pos[n] for n in order], dtype=np.int64)
        return cls(
            depot_node_id=depot_node_id,
            stops=tuple(stops),
            vehicles=tuple(vehicles),
            distance=_to_array(distance_m)[np.ix_(idx, idx)],
            duration=_to_array(duration_s)[np.ix_(idx, idx)],
        )


@dataclass
class Route:
    vehicle: Vehicle
    stops: list[int] = field(default_factory=list)   # problem indeksleri, depo hariç


@dataclass
class Solution:
    algorithm: str
    routes: list[Route]
    computation_time_ms: float = 0.0


def _to_array(rows: Sequence[Sequence[float | None]]) -> np.ndarray:
    """null -> inf."""
    return np.array([[math.inf if v is None else float(v) for v in r] for r in rows],
                    dtype=float)
