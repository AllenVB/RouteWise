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
from typing import ClassVar
from functools import cached_property

import numpy as np

DEPOT = 0


class InfeasibleError(Exception):
    """Sert kısıtlar bu girdiyle karşılanamıyor — istemci akışı değiştirmeli."""


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
    fuel_l_per_100km: float = 0.0
    fixed_cost_per_day: float = 0.0


@dataclass(frozen=True)
class CostParams:
    """
    Maliyet modeli parametreleri — backend'deki `cost_parameters` satırı.

    Kod değişmeden fiyat değişince sonuç değişsin diye her istekte gelir
    (mimari §9).
    """

    fuel_price_per_l: float
    driver_cost_per_hour: float
    priority_penalty: float = 0.0


@dataclass(frozen=True)
class Problem:
    depot_node_id: int
    stops: tuple[Stop, ...]
    vehicles: tuple[Vehicle, ...]
    distance: np.ndarray      # float64[n+1, n+1] — metre, inf = yol yok
    duration: np.ndarray      # float64[n+1, n+1] — saniye
    # None: maliyet modeli yok, amaç toplam mesafedir (Faz 2 davranışı).
    costs: CostParams | None = None

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

    # --- sıcak yol: yerel arama ve SA amaç fonksiyonunu çok kez çağırır ---
    # numpy skalerine Python döngüsünden erişmek düz listeden belirgin yavaştır.

    @cached_property
    def dist_rows(self) -> list[list[float]]:
        return self.distance.tolist()

    @cached_property
    def dur_rows(self) -> list[list[float]]:
        return self.duration.tolist()

    @cached_property
    def demand(self) -> list[float]:
        """Problem indeksiyle; 0 (depo) için 0."""
        return [0.0, *(s.demand_kg for s in self.stops)]

    @cached_property
    def service(self) -> list[float]:
        return [0.0, *(s.service_time_s for s in self.stops)]

    @cached_property
    def priority(self) -> list[int]:
        return [0, *(s.priority for s in self.stops)]

    # Granüler komşuluk: yerel arama bir durağı yalnızca en yakın K durağın (ve
    # deponun) yanına taşımayı dener. Uzak bir noktanın yanına taşımak neredeyse
    # hiç iyileştirme getirmez ama aday sayısını n² mertebesinde büyütür.
    granular_k: ClassVar[int] = 12

    @cached_property
    def near(self) -> list[frozenset[int]]:
        """Problem indeksiyle: i -> en yakın K durak + depo. Ölçü iki yönün toplamı (asimetrik matris)."""
        d = self.dist_rows
        n = self.size
        out: list[frozenset[int]] = [frozenset(range(n + 1))]          # depo için kısıt yok
        for i in range(1, n + 1):
            others = sorted((j for j in range(1, n + 1) if j != i), key=lambda j: (d[i][j] + d[j][i], j))
            out.append(frozenset([DEPOT, *others[:self.granular_k]]))
        return out

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
        costs: CostParams | None = None,
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
            costs=costs,
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
